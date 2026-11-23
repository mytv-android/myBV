package com.github.mytv.bv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.github.mytv.bv.player.entity.SpatialAudioMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class SpatialAudioProcessorTest {
    @Test
    fun `decoder multichannel cannot become eligible by the sinks stereo channel mapping`() {
        val processor = SpatialAudioProcessor(nativeReady = true) { _, _ -> true }
        processor.sourceFormat(androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW).setChannelCount(6).build())
        val stereo = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(AudioFormat.NOT_SET, processor.configure(stereo))
        processor.sourceFormat(androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW).setChannelCount(2).build())
        assertEquals(6, processor.configure(stereo).channelCount)
        processor.reset()
    }

    @Test
    fun `unsupported route format or disabled mode retains the input configuration`() {
        val stereo = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(AudioFormat.NOT_SET, SpatialAudioProcessor(nativeReady = false) { _, _ -> true }.configure(stereo))
        assertEquals(AudioFormat.NOT_SET, SpatialAudioProcessor(nativeReady = true) { _, _ -> false }.configure(stereo))
        assertEquals(AudioFormat.NOT_SET, SpatialAudioProcessor(mode = SpatialAudioMode.Off, nativeReady = true) { _, _ -> true }.configure(stereo))
        assertEquals(AudioFormat.NOT_SET, SpatialAudioProcessor(nativeReady = true) { _, _ -> true }
            .configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET, SpatialAudioProcessor(nativeReady = true) { _, _ -> true }
            .configure(AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)))
    }

    @Test
    fun `real adaptive JNI produces identical output across byte splits`() {
        if (!nativePresent()) return
        val source = ByteArray(8192).also { java.util.Random(7).nextBytes(it) }
        val whole = processor()
        whole.queueInput(direct(source))
        val expected = read(whole.output)
        val split = processor()
        val actual = mutableListOf<Byte>()
        for (part in source.toList().chunked(37)) {
            val input = direct(part.toByteArray())
            split.queueInput(input)
            assertEquals(input.limit(), input.position())
            actual.addAll(read(split.output).toList())
        }
        assertEquals(source.size * 3, expected.size)
        assertContentEquals(expected, actual.toByteArray())
        whole.reset(); split.reset()
    }

    @Test
    fun `seek flush removes delayed ambience and incomplete stereo frames`() {
        if (!nativePresent()) return
        val processor = processor()
        processor.queueInput(direct(ByteArray(40000).also { java.util.Random(9).nextBytes(it) }))
        processor.output
        processor.queueInput(direct(byteArrayOf(1, 2, 3)))
        processor.flush()
        processor.queueInput(direct(ByteArray(8000)))
        assertContentEquals(ByteArray(24000), read(processor.output))
        processor.reset()
    }

    @Test
    fun `end of stream drops partial frame without inventing surround tail frames`() {
        val processor = processor()
        processor.queueInput(direct(byteArrayOf(1, 2)))
        processor.queueEndOfStream()
        assertEquals(0, processor.output.remaining())
        assertTrue(processor.isEnded)
        processor.reset()
    }

    @Test
    fun `sample rate and encoding changes do not reuse old ambience`() {
        if (!nativePresent()) return
        val processor = processor()
        processor.queueInput(direct(ByteArray(16000).also { java.util.Random(2).nextBytes(it) }))
        processor.output
        assertEquals(AudioFormat(44100, 6, C.ENCODING_PCM_FLOAT),
            processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)))
        processor.flush()
        processor.queueInput(direct(ByteArray(800)))
        assertContentEquals(ByteArray(2400), read(processor.output))
        processor.reset()
    }

    @Test
    fun `unusable native buffer keeps stereo in the negotiated front channels`() {
        val processor = processor()
        val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        processor.queueInput(input)
        assertContentEquals(byteArrayOf(1, 2, 3, 4) + ByteArray(8), read(processor.output))
        assertEquals(input.limit(), input.position())
        processor.reset()
    }

    @Test
    fun `native session rejects readonly output without consuming input and closes safely`() {
        if (!nativePresent()) return
        val session = assertNotNull(NativeAdaptiveUpmix.create(48000, com.github.mytv.bv.player.entity.StereoUpmixSettings()))
        val input = direct(ByteArray(8))
        assertFalse(session.process(input, 8, ByteBuffer.allocateDirect(24).asReadOnlyBuffer(), PcmGainEncoding.Float32))
        assertEquals(0, input.position())
        assertTrue(session.process(input, 8, ByteBuffer.allocateDirect(24), PcmGainEncoding.Float32))
        session.close(); session.close()
        input.rewind()
        assertFalse(session.process(input, 8, ByteBuffer.allocateDirect(24), PcmGainEncoding.Float32))
    }

    private fun nativePresent(): Boolean {
        if (java.lang.Boolean.getBoolean("mybv.requireNativeAudio")) assertTrue(NativeAdaptiveUpmix.available)
        return NativeAdaptiveUpmix.available
    }
    private fun processor() = SpatialAudioProcessor(nativeReady = true) { _, _ -> true }.apply {
        assertEquals(6, configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)).channelCount)
        flush()
    }
    private fun direct(bytes: ByteArray) = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        .apply { put(bytes); flip() }
    private fun read(input: ByteBuffer) = ByteArray(input.remaining()).also { input.get(it) }
}
