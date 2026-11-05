package com.github.mytv.bv.player.audio

import java.nio.ByteBuffer

/**
 * 可选原生后端的唯一 JNI 边界。一次借用两个互不重叠的 direct buffer，不保留地址。
 * API 版本不匹配、未打包当前 ABI 或输入不是 direct buffer 时返回 NaN，由调用方回退。
 */
internal object NativePcmGain {
    @Volatile
    private var callable = true

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("mybv_audio")
            nativeApiVersion() == API_VERSION
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        encoding: PcmGainEncoding,
        channels: Int,
        currentGain: Double,
        targetGain: Double,
        stepPerFrame: Double,
    ): Double {
        if (!input.isDirect || !output.isDirect || output.isReadOnly ||
            output.remaining() < input.remaining() || !callable || !isAvailable
        ) return Double.NaN

        return try {
            nativeProcess(
                input, input.position(), input.remaining(), output, output.position(),
                encoding.nativeId, channels, currentGain, targetGain, stepPerFrame
            )
        } catch (_: UnsatisfiedLinkError) {
            // 旧二进制即使能返回版本号，也可能缺少实际运算入口；不在每个音频块重复抛错。
            callable = false
            Double.NaN
        }
    }

    @JvmStatic
    private external fun nativeApiVersion(): Int

    @JvmStatic
    private external fun nativeProcess(
        input: ByteBuffer,
        inputOffset: Int,
        length: Int,
        output: ByteBuffer,
        outputOffset: Int,
        encoding: Int,
        channels: Int,
        currentGain: Double,
        targetGain: Double,
        stepPerFrame: Double,
    ): Double

    private const val API_VERSION = 1
}
