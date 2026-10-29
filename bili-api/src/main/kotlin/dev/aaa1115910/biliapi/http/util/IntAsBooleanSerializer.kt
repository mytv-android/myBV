package dev.aaa1115910.biliapi.http.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * 用于处理 API 返回 Int(0/1) 或 Boolean(true/false)、代码期望 Boolean 的情况。
 *
 * B 站的动态流、用户卡片等接口会把 `following` 之类的布尔字段有时返回成 `0`/`1`、
 * 有时返回成 `true`/`false`，直接用 Boolean 反序列化会抛异常，进而导致整页数据
 * （动态列表、视频详情）解析失败。
 */
object IntAsBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntAsBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        // 不能「先 decodeInt 失败再 decodeBoolean」：解码器是单向流，第一次读取已经把
        // token 吃掉了，第二次读会错位（实测抛 IndexOutOfBoundsException，
        // 动态页 26 条全部解码失败、页面全白）。这里只读一次，按原值判断。
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive ->
                element.intOrNull?.let { it != 0 } ?: element.booleanOrNull ?: false

            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}
