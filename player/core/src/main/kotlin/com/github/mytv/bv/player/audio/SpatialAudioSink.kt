package com.github.mytv.bv.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Decode eligible stereo, while retaining original multichannel/object paths. */
@UnstableApi
internal class SpatialAudioSink(
    sink: AudioSink,
    private val sourceFormatObserver: (Format) -> Unit = {},
    private val canOutput51: (Format) -> Boolean = {
        SpatialAudioProcessor.supportsDirect51(it.sampleRate, C.ENCODING_PCM_16BIT)
    },
) : ForwardingAudioSink(sink) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        sourceFormatObserver(config.format)
        super.configure(config)
    }
    override fun supportsFormat(format: Format): Boolean =
        if (requiresStereoDecode(format)) false else super.supportsFormat(format)
    override fun getFormatSupport(format: Format): Int =
        if (requiresStereoDecode(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        if (requiresStereoDecode(format) || (format.channelCount == 2 &&
                format.sampleMimeType == MimeTypes.AUDIO_RAW && canOutput51(format)))
            AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)
    private fun requiresStereoDecode(format: Format): Boolean =
        format.channelCount == 2 && format.sampleMimeType != null && canOutput51(format) &&
            format.sampleMimeType != MimeTypes.AUDIO_RAW && format.sampleMimeType !in
            listOf(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC4)
}
