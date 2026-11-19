package com.github.mytv.bv.player.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.github.mytv.bv.player.entity.SpatialAudioMode
import com.github.mytv.bv.player.entity.StereoUpmixSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Negotiates 5.1 only for stereo PCM and an available output route. */
@UnstableApi
internal class SpatialAudioProcessor(
    private val mode: SpatialAudioMode = SpatialAudioMode.Surround51,
    private val settings: StereoUpmixSettings = StereoUpmixSettings(),
    private val nativeReady: Boolean = if (mode == SpatialAudioMode.Matrix51)
        NativeSpatialAudio.isAvailable else NativeAdaptiveUpmix.available,
    private val supportsOutput: (Int, Int) -> Boolean = ::supportsDirect51,
) : BaseAudioProcessor() {
    private val carry = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
    private var carried = 0
    private var enabled = false
    private var pendingEnabled = false
    private var encoding = PcmGainEncoding.Signed16
    private var pendingEncoding = PcmGainEncoding.Signed16
    private var sampleRate = 0
    private var pendingRate = 0
    private var sourceStereo = true
    private var session: NativeAdaptiveUpmix.Session? = null
    val backendAvailable get() = nativeReady
    @Volatile var status = "off"
        private set

    fun sourceFormat(format: Format) {
        // Observe before DefaultAudioSink's channel mapper: a decoder's native
        // multichannel output must not be folded to stereo and upmixed again.
        sourceStereo = format.channelCount == 2 && format.sampleMimeType == MimeTypes.AUDIO_RAW
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingEnabled = mode != SpatialAudioMode.Off && nativeReady && sourceStereo &&
            inputAudioFormat.channelCount == 2 && inputAudioFormat.sampleRate in 8000..192000 &&
            inputAudioFormat.encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) &&
            supportsOutput(inputAudioFormat.sampleRate, inputAudioFormat.encoding)
        if (!pendingEnabled) {
            status = "original audio (mode, backend, input or 5.1 route unavailable)"
            return AudioProcessor.AudioFormat.NOT_SET
        }
        pendingRate = inputAudioFormat.sampleRate
        pendingEncoding = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
            PcmGainEncoding.Float32 else PcmGainEncoding.Signed16
        status = if (mode == SpatialAudioMode.Matrix51) "Rust fixed matrix -> 5.1 PCM"
            else "Rust four-band adaptive -> 5.1 PCM"
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 6, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!enabled) {
            replaceOutputBuffer(inputBuffer.remaining()).apply { put(inputBuffer); flip() }
            return
        }
        val frameBytes = encoding.bytesPerSample * 2
        val frames = (carried.toLong() + inputBuffer.remaining()) / frameBytes
        require(frames <= Int.MAX_VALUE / (encoding.bytesPerSample * 6))
        val out = replaceOutputBuffer((frames * encoding.bytesPerSample * 6).toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        if (carried > 0) {
            while (carried < frameBytes && inputBuffer.hasRemaining()) carry.put(carried++, inputBuffer.get())
            if (carried == frameBytes) {
                carry.position(0); carry.limit(frameBytes)
                render(carry, frameBytes, out)
                carried = 0; carry.clear()
            }
        }
        val complete = inputBuffer.remaining() / frameBytes * frameBytes
        if (complete > 0) render(inputBuffer, complete, out)
        while (inputBuffer.hasRemaining()) carry.put(carried++, inputBuffer.get())
        out.flip()
    }

    private fun render(input: ByteBuffer, length: Int, output: ByteBuffer) {
        val success = if (mode == SpatialAudioMode.Matrix51)
            NativeSpatialAudio.upmix51(input, length, output, encoding)
        else session?.process(input, length, output, encoding) == true
        if (success) return
        session?.close(); session = null
        status = "processing unavailable; original stereo in negotiated front channels"
        val end = input.position() + length
        while (input.position() < end) {
            repeat(encoding.bytesPerSample * 2) { output.put(input.get()) }
            repeat(encoding.bytesPerSample * 4) { output.put(0.toByte()) }
        }
    }

    override fun onFlush() {
        carried = 0; carry.clear()
        enabled = pendingEnabled
        encoding = pendingEncoding
        if (enabled && mode == SpatialAudioMode.Surround51) {
            if (sampleRate != pendingRate || session?.reset() != true) {
                session?.close()
                session = NativeAdaptiveUpmix.create(pendingRate, settings)
            }
        } else { session?.close(); session = null }
        sampleRate = pendingRate
    }

    override fun onQueueEndOfStream() { carried = 0; carry.clear() }
    override fun onReset() {
        session?.close(); session = null
        enabled = false; pendingEnabled = false
        carried = 0; sampleRate = 0; pendingRate = 0; carry.clear()
        status = "off"
        sourceStereo = true
    }

    companion object {
        fun supportsDirect51(rate: Int, encoding: Int): Boolean {
            if (Build.VERSION.SDK_INT < 29 || rate !in 8000..192000) return false
            return runCatching {
                AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build(),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            }.getOrDefault(false)
        }
    }
}
