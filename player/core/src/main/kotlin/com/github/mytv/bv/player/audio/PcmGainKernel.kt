package com.github.mytv.bv.player.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 处理一个 PCM 块；调用期间独占缓冲，不持有缓冲或改变声道、采样率和时间戳。 */
internal interface PcmGainKernel {
    val name: String

    fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        encoding: PcmGainEncoding,
        channels: Int,
        currentGain: Double,
        targetGain: Double,
        stepPerFrame: Double,
    ): Double
}

internal enum class PcmGainEncoding(val nativeId: Int, val bytesPerSample: Int) {
    Signed16(1, 2),
    Float32(2, 4),
}

/** 保留 JVM 实现，供默认构建、不支持的 ABI 和原生库不可用时使用。 */
internal object KotlinPcmGainKernel : PcmGainKernel {
    override val name = "Kotlin"

    override fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        encoding: PcmGainEncoding,
        channels: Int,
        currentGain: Double,
        targetGain: Double,
        stepPerFrame: Double,
    ): Double {
        require(channels > 0)
        require(output.remaining() >= input.remaining())
        if (currentGain == 1.0 && targetGain == 1.0) {
            output.put(input)
            return currentGain
        }

        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        require(channels <= Int.MAX_VALUE / encoding.bytesPerSample)
        val frameBytes = encoding.bytesPerSample * channels
        val frameCount = source.remaining() / frameBytes
        var gain = currentGain
        repeat(frameCount) {
            if (gain != targetGain) {
                gain = if (stepPerFrame >= 0.0) {
                    minOf(targetGain, gain + stepPerFrame)
                } else {
                    maxOf(targetGain, gain + stepPerFrame)
                }
            }
            repeat(channels) {
                when (encoding) {
                    PcmGainEncoding.Float32 ->
                        output.putFloat((source.float * gain).coerceIn(-1.0, 1.0).toFloat())
                    PcmGainEncoding.Signed16 -> output.putShort(
                        (source.short * gain)
                            .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                            .toInt()
                            .toShort()
                    )
                }
            }
        }
        while (source.hasRemaining()) output.put(source.get())
        input.position(input.limit())
        return gain
    }
}

/** 每次原生调用失败都用完整输入重新计算；原生实现不移动 Java 缓冲的 position。 */
internal class OptionalRustPcmGainKernel : PcmGainKernel {
    // 在播放器创建时加载库，避免音频回调首次触发动态链接和 API 探测。
    private val nativeAvailable = NativePcmGain.isAvailable

    @Volatile
    override var name = "Kotlin"
        private set

    override fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        encoding: PcmGainEncoding,
        channels: Int,
        currentGain: Double,
        targetGain: Double,
        stepPerFrame: Double,
    ): Double {
        val inputSize = input.remaining()
        val result = if (nativeAvailable) {
            NativePcmGain.process(
                input, output, encoding, channels, currentGain, targetGain, stepPerFrame
            )
        } else Double.NaN
        if (result.isFinite()) {
            input.position(input.position() + inputSize)
            output.position(output.position() + inputSize)
            name = "Rust"
            return result
        }
        name = "Kotlin"
        return KotlinPcmGainKernel.process(
            input, output, encoding, channels, currentGain, targetGain, stepPerFrame
        )
    }
}
