package com.github.mytv.bv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.github.mytv.bv.player.core.BuildConfig
import com.github.mytv.bv.player.entity.AudioBalanceLevel
import com.github.mytv.bv.player.entity.AudioLoudness
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * 音量均衡处理器。
 *
 * 只做一件事：把 [AudioBalanceGain] 算出的那个静态增益乘到每个采样上。没有响度测量、
 * 没有统计窗口、没有限制器——因此不会产生抽吸感，也不改变音调与音色。
 *
 * 增益是静态的、与播放位置无关，所以 seek/变速（[onFlush]）不需要重置它；只有换挡或换集
 * （[setLevel] / [setLoudness]）才用 [RAMP_SECONDS] 的线性斜坡过去，避免爆音。
 *
 * 旁路（关闭挡位或无元数据）时走整块搬运，不做任何逐采样运算。
 */
@UnstableApi
internal class VolumeBalanceAudioProcessor(
    level: AudioBalanceLevel = AudioBalanceLevel.Off,
    private val gainKernel: PcmGainKernel = if (BuildConfig.ENABLE_RUST_AUDIO) {
        OptionalRustPcmGainKernel()
    } else {
        KotlinPcmGainKernel
    },
) : BaseAudioProcessor() {

    /** 最近一次 PCM 增益块使用的后端；不表示压缩码流经过了处理。 */
    val processingBackend: String
        get() = gainKernel.name

    @Volatile
    private var level: AudioBalanceLevel = level

    @Volatile
    private var loudness: AudioLoudness? = null

    private var sampleRateHz: Int = DEFAULT_SAMPLE_RATE
    private var channelCount: Int = DEFAULT_CHANNEL_COUNT
    private var encoding: Int = C.ENCODING_PCM_16BIT

    /** 策略给出的目标增益（线性） */
    @Volatile
    private var targetGain: Double = 1.0

    /** 当前实际增益（线性），逐帧按 [gainStepPerFrame] 朝 [targetGain] 走 */
    private var currentGain: Double = 1.0

    /** 每帧增益步进；换挡在控制线程计算，音频线程读取 */
    @Volatile
    private var gainStepPerFrame: Double = 0.0

    fun setLevel(level: AudioBalanceLevel) {
        if (this.level == level) return
        this.level = level
        recompute()
    }

    fun setLoudness(loudness: AudioLoudness?) {
        if (this.loudness == loudness) return
        this.loudness = loudness
        recompute()
    }

    private fun recompute() {
        val gainDb = loudness?.let { AudioBalanceGain.gainDb(level, it) }
        targetGain = if (gainDb == null) 1.0 else 10.0.pow(gainDb / 20.0)
        updateStep()
    }

    private fun updateStep() {
        gainStepPerFrame = (targetGain - currentGain) / (sampleRateHz * RAMP_SECONDS)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // 旁路：整块搬运，不碰采样
        if (targetGain == 1.0 && currentGain == 1.0) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(inputBuffer.remaining())
        // 每块读取同一个参数快照，两个后端使用相同的斜坡规则。
        currentGain = gainKernel.process(
            input = inputBuffer,
            output = out,
            encoding = if (encoding == C.ENCODING_PCM_FLOAT) {
                PcmGainEncoding.Float32
            } else {
                PcmGainEncoding.Signed16
            },
            channels = channelCount,
            currentGain = currentGain,
            targetGain = targetGain,
            stepPerFrame = gainStepPerFrame,
        )
        out.flip()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                encoding = inputAudioFormat.encoding
                sampleRateHz = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                channelCount =
                    inputAudioFormat.channelCount.takeIf { it > 0 } ?: DEFAULT_CHANNEL_COUNT
                // 起播即准：直接落到目标增益，不从 1.0 爬上去
                currentGain = targetGain
                updateStep()
                inputAudioFormat
            }

            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        // 静态增益与播放位置无关，seek/变速后沿用当前值即可
        currentGain = targetGain
        updateStep()
    }

    override fun onReset() {
        currentGain = targetGain
        updateStep()
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2

        /** 换挡/换集时的线性斜坡时长 */
        const val RAMP_SECONDS = 0.05
    }
}
