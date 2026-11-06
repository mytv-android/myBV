package com.github.mytv.bv.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/** A six-channel PCM bed -> software E-AC-3 outlet, after the sink's DSP chain. */
@UnstableApi
internal class Eac3AudioOutputProvider(context: Context,minBufferMs: Int=80,maxBufferMs: Int=160) : AudioOutputProvider {
    private val delegate=AudioTrackAudioOutputProvider.Builder(context)
        .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(minBufferMs*1000).setMaxPcmBufferDurationUs(maxBufferMs*1000)
            .setPcmBufferMultiplicationFactor(2).build()).build()
    private val listeners=CopyOnWriteArraySet<AudioOutputProvider.Listener>()
    private val virtualConfigurations=java.util.Collections.synchronizedSet(linkedSetOf<AudioOutputProvider.OutputConfig>())
    @Volatile private var disabled=false
    @Volatile private var sourceStereo=false
    @Volatile var status="PCM / original bitstream"
        private set

    fun sourceFormat(format: Format) {
        sourceStereo=format.channelCount==2 && format.sampleMimeType==MimeTypes.AUDIO_RAW
        virtualConfigurations.clear()
    }
    fun supports51(sampleRate: Int,encoding: Int): Boolean =
        SpatialAudioProcessor.supportsDirect51(sampleRate,encoding) ||
            (encoding==C.ENCODING_PCM_16BIT && supportsSoftware51(sampleRate))
    private fun supportsSoftware51(rate: Int): Boolean {
        if (disabled || !NativeEac3.available || Build.VERSION.SDK_INT<29 || rate !in 8000..192000) return false
        return runCatching { AudioTrack.isDirectPlaybackSupported(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_E_AC3).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build(),
            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build()) }.getOrDefault(false)
    }
    private fun virtual(config: AudioOutputProvider.FormatConfig): Boolean = sourceStereo && !config.enableTunneling &&
        supportsSoftware51(config.format.sampleRate) &&
        config.format.sampleMimeType==MimeTypes.AUDIO_RAW && config.format.channelCount==6 && config.format.sampleRate in 8000..192000 &&
        config.format.pcmEncoding==C.ENCODING_PCM_16BIT &&
        !SpatialAudioProcessor.supportsDirect51(config.format.sampleRate,C.ENCODING_PCM_16BIT)
    override fun getFormatSupport(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        if (virtual(config)) AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING).build()
        else delegate.getFormatSupport(config)

    override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (!virtual(config)) return delegate.getOutputConfig(config)
        val output=AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(config.format.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .setBufferSize((config.format.sampleRate*12*128/1000).coerceAtLeast(1536*12))
            .setAudioAttributes(config.audioAttributes).setAudioSessionId(config.audioSessionId)
            .setVirtualDeviceId(config.virtualDeviceId).setIsOffload(false).setIsTunneling(false)
            .setUsePlaybackParameters(false).setUseOffloadGapless(false).build()
        synchronized(virtualConfigurations) {
            if (virtualConfigurations.size>=8) virtualConfigurations.clear()
            virtualConfigurations+=output
        }
        return output
    }
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        if (config !in virtualConfigurations) return delegate.getAudioOutput(config)
        val encoder=if (supportsSoftware51(config.sampleRate)) NativeEac3.create(config.sampleRate) else null
        if (encoder!=null) {
            try {
                val minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_5POINT1,AudioFormat.ENCODING_E_AC3).coerceAtLeast(0)
                val encoded=config.buildUpon().setEncoding(AudioFormat.ENCODING_E_AC3).setSampleRate(48000)
                    .setBufferSize(maxOf(minimum,12800,5120)).setUsePlaybackParameters(false).build()
                val child=delegate.getAudioOutput(encoded)
                status="software DD+ 5.1 bed (no Atmos objects)"
                return EncodedPcmOutput(child,encoder,config.sampleRate,config.bufferSize/12) { disable() }
            } catch (_: Exception) { encoder.close() }
        }
        disable()
        val stereo=config.buildUpon().setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize((config.bufferSize/3).coerceAtLeast(4096)).build()
        status="encoder unavailable; stereo fold-down"
        return StereoFoldDownOutput(delegate.getAudioOutput(stereo))
    }
    private fun disable() {
        disabled=true
        // Do not re-enter DefaultAudioSink configuration from an initialization failure.
        // Existing virtual six-channel streams remain consumable by the stereo fallback;
        // the next configuration sees supports51=false and returns to the ordinary path.
    }
    override fun addListener(listener: AudioOutputProvider.Listener) { listeners+=listener; delegate.addListener(listener) }
    override fun removeListener(listener: AudioOutputProvider.Listener) { listeners-=listener; delegate.removeListener(listener) }
    override fun setClock(clock: Clock) { delegate.setClock(clock) }
    override fun hasPendingReleases()=delegate.hasPendingReleases()
    override fun release() { virtualConfigurations.clear(); listeners.clear(); delegate.release() }
}

@UnstableApi
private class EncodedPcmOutput(private val child: AudioOutput,private var encoder: NativeEac3.Session?,
    private val sourceRate: Int,private val bufferFrames: Int,private val failed: ()->Unit) : AudioOutput by child {
    private val packet=ByteBuffer.allocateDirect(32768).apply { limit(0) }
    private var sourceFrames=0L
    private var encodedFrames=0L
    private var firstPts=C.TIME_UNSET
    private var packetPts=0L
    private var delayUs=(encoder?.delayOutputFrames ?: 0)*1_000_000L/48000
    private var ending=false; private var ended=false; private var stopped=false; private var released=false
    override fun write(buffer: ByteBuffer,encodedAccessUnitCount: Int,presentationTimeUs: Long): Boolean {
        if (released || ending || encoder==null) return failure()
        if (buffer.remaining()%12!=0 || !buffer.isDirect) throw AudioOutput.WriteException(AudioTrack.ERROR_BAD_VALUE,false)
        if (firstPts==C.TIME_UNSET) firstPts=presentationTimeUs.takeUnless { it==C.TIME_UNSET } ?: 0L
        pump()
        if (packet.hasRemaining()) return false
        repeat(16) {
            if (!buffer.hasRemaining()) return true
            val consumed=encoder?.push(buffer) ?: -22
            if (consumed<0) return failure()
            sourceFrames+=consumed
            pump()
            if (consumed==0 || packet.hasRemaining()) return !buffer.hasRemaining()
        }
        return !buffer.hasRemaining()
    }
    private fun pump() {
        if (released || encoder==null) return
        repeat(32) {
            if (packet.hasRemaining()) {
                if (!child.write(packet,1,packetPts)) return
                if (packet.hasRemaining()) return
            }
            if (ended) { if (ending && !stopped) { child.stop(); stopped=true }; return }
            packet.clear()
            when (val bytes=encoder?.read(packet) ?: -22) {
                0 -> { packet.limit(0); return }
                -1 -> { packet.limit(0); ended=true }
                else -> {
                    if (bytes<0) { failure(); return }
                    packet.flip()
                    val info=Eac3FrameInfo.parse(packet) ?: run { failure(); return }
                    packetPts=(if (firstPts==C.TIME_UNSET) 0L else firstPts)+encodedFrames*1_000_000/48000-delayUs
                    encodedFrames+=info.samples
                }
            }
        }
    }
    private fun failure(): Nothing {
        failed()
        throw AudioOutput.WriteException(AudioTrack.ERROR_DEAD_OBJECT,true)
    }
    override fun getPositionUs(): Long {
        pump()
        val position=child.positionUs
        if (position==C.TIME_UNSET) return position
        return (position-delayUs).coerceIn(0,sourceFrames*1_000_000/sourceRate)
    }
    override fun getSampleRate()=sourceRate
    override fun getBufferSizeInFrames()=bufferFrames.toLong()
    override fun isOffloadedPlayback()=false
    override fun getPlaybackParameters()=PlaybackParameters.DEFAULT
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { /* Sink applies Sonic before encoding. */ }
    override fun isStalled(): Boolean { pump(); return child.isStalled }
    override fun flush() {
        child.flush(); encoder?.close(); encoder=NativeEac3.create(sourceRate)
        sourceFrames=0; encodedFrames=0; firstPts=C.TIME_UNSET; ending=false; ended=false; stopped=false; packet.clear().limit(0)
        delayUs=(encoder?.delayOutputFrames ?: 0)*1_000_000L/48000
        if (encoder==null) failed()
    }
    override fun stop() { ending=true; encoder?.finish(); pump() }
    override fun release() { if (!released) { released=true; encoder?.close(); encoder=null; child.release() } }
}

@UnstableApi
private class StereoFoldDownOutput(private val child: AudioOutput) : AudioOutput by child {
    private val stereo=ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    private var pendingBytes=0
    private var chunkPts=0L
    private var owner: ByteBuffer?=null
    private var initialPosition=0
    private var initialPts=C.TIME_UNSET
    override fun write(buffer: ByteBuffer,encodedAccessUnitCount: Int,presentationTimeUs: Long): Boolean {
        if (buffer.remaining()%12!=0) throw AudioOutput.WriteException(AudioTrack.ERROR_BAD_VALUE,false)
        if (owner!==buffer || initialPts!=presentationTimeUs) { check(pendingBytes==0); owner=buffer; initialPosition=buffer.position(); initialPts=presentationTimeUs }
        repeat(16) {
            if (pendingBytes>0) {
                if (!child.write(stereo,1,chunkPts)) return false
                buffer.position(buffer.position()+pendingBytes); pendingBytes=0
            }
            if (!buffer.hasRemaining()) return true
            val frames=minOf(buffer.remaining()/12,1024)
            stereo.clear()
            val input=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) {
                val left=input.short.toDouble(); val right=input.short.toDouble(); val centre=input.short.toDouble()
                input.short // LFE is handled by receiver bass management, not added twice.
                val surroundLeft=input.short.toDouble(); val surroundRight=input.short.toDouble()
                stereo.putShort((left+0.7071067811865476*(centre+surroundLeft)).coerceIn(-32768.0,32767.0).toInt().toShort())
                stereo.putShort((right+0.7071067811865476*(centre+surroundRight)).coerceIn(-32768.0,32767.0).toInt().toShort())
            }
            stereo.flip(); pendingBytes=frames*12
            chunkPts=presentationTimeUs+(buffer.position()-initialPosition)/12*1_000_000L/child.sampleRate
        }
        return !buffer.hasRemaining()
    }
    override fun flush() { pendingBytes=0; stereo.clear().limit(0); owner=null; child.flush() }
}

internal data class Eac3FrameInfo(val bytes: Int,val samples: Int) {
    companion object {
        fun parse(input: ByteBuffer): Eac3FrameInfo? {
            if (input.remaining()<6) return null
            val offset=input.position()
            fun b(index: Int)=input.get(offset+index).toInt() and 255
            if (b(0)!=0x0b || b(1)!=0x77 || (b(5) shr 3) !in 11..16) return null
            val bytes=((((b(2) and 7) shl 8) or b(3))+1)*2
            if (bytes!=input.remaining()) return null
            val blocks=if ((b(4) shr 6)==3) 6 else intArrayOf(1,2,3,6)[(b(4) shr 4) and 3]
            return Eac3FrameInfo(bytes,blocks*256)
        }
    }
}
