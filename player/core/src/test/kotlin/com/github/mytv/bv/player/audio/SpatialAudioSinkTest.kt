package com.github.mytv.bv.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialAudioSinkTest {
    private val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
        when (method.name) {
            "supportsFormat" -> true
            "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            "getFormatOffloadSupport" -> AudioOffloadSupport.Builder().setIsFormatSupported(true).build()
            else -> error("Unexpected sink operation: ${method.name}")
        }
    } as AudioSink
    private val sink = SpatialAudioSink(delegate) { true }

    @Test
    fun `stereo encoded audio must be decoded and PCM must reach processors`() {
        val stereo = format(MimeTypes.AUDIO_AAC, 2)
        assertFalse(sink.supportsFormat(stereo))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(stereo))
        assertFalse(sink.getFormatOffloadSupport(stereo).isFormatSupported)
        assertTrue(sink.supportsFormat(format(MimeTypes.AUDIO_RAW, 2)))
        assertFalse(sink.getFormatOffloadSupport(format(MimeTypes.AUDIO_RAW, 2)).isFormatSupported)
    }
    @Test
    fun `original multichannel and object codecs preserve pass through and offload`() {
        for ((mime, channels) in listOf(MimeTypes.AUDIO_E_AC3 to 6, MimeTypes.AUDIO_E_AC3_JOC to 2,
            MimeTypes.AUDIO_TRUEHD to 8, MimeTypes.AUDIO_AC4 to 2)) {
            val original = format(mime, channels)
            assertTrue(sink.supportsFormat(original))
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(original))
            assertTrue(sink.getFormatOffloadSupport(original).isFormatSupported)
        }
    }
    @Test
    fun `route without six channel PCM retains original stereo output path`() {
        val passthrough = SpatialAudioSink(delegate) { false }
        val stereo = format(MimeTypes.AUDIO_E_AC3, 2)
        assertTrue(passthrough.supportsFormat(stereo))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, passthrough.getFormatSupport(stereo))
        assertTrue(passthrough.getFormatOffloadSupport(stereo).isFormatSupported)
    }
    private fun format(mime: String, channels: Int) = Format.Builder()
        .setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48000).build()
}
