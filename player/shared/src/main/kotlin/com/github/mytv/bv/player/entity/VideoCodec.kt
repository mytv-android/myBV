package com.github.mytv.bv.player.entity

import android.content.Context
import dev.aaa1115910.biliapi.entity.CodeType
import com.github.mytv.bv.player.shared.R

enum class VideoCodec(private val strRes: Int, val prefixes: List<String>, val codecId: Int) {
    AVC(R.string.video_codec_avc, listOf("avc1"), 7),
    HEVC(R.string.video_codec_hevc, listOf("hev1", "hvc1"), 12),
    AV1(R.string.video_codec_av1, listOf("av01"), 13),
    DVH1(R.string.video_codec_dvh1, listOf("dvh1"), 0),
    HVC1(R.string.video_codec_hvc1, listOf("hvc1"), 0);

    companion object {
        fun fromCode(code: Int?) = runCatching {
            entries.find { it.ordinal == code }!!
        }.getOrDefault(AVC)

        /**
         * B 站现在普通 HEVC 流的 codec 字符串也是 `hvc1.*`，只有杜比视界清晰度才应映射成 HVC1。
         */
        fun fromCodecString(codec: String, dolbyVisionQuality: Boolean = false) = when {
            codec.startsWith("avc1") -> AVC
            codec.startsWith("hev1") -> HEVC
            codec.startsWith("av01") -> AV1
            codec.startsWith("dvh1") -> DVH1
            codec.startsWith("hvc1") -> if (dolbyVisionQuality) HVC1 else HEVC
            else -> null
        }

        fun fromCodecId(codecId: Int) = runCatching {
            entries.find { it.codecId == codecId }!!
        }.getOrDefault(AVC)
    }

    fun matches(codec: String) = prefixes.any { codec.startsWith(it) }

    fun getDisplayName(context: Context) = context.getString(strRes)

    fun toBiliApiCodeType() = when (this) {
        AVC -> CodeType.Code264
        HEVC -> CodeType.Code265
        AV1 -> CodeType.CodeAv1
        DVH1, HVC1 -> CodeType.Code265
    }
}