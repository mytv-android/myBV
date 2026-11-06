package com.github.mytv.bv.player.audio

import java.nio.ByteBuffer

internal object NativeSpatialAudio {
    @Volatile private var callable = true
    val isAvailable: Boolean by lazy {
        if (!NativePcmGain.isAvailable) false else try {
            nativeApiVersion() == 1
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun upmix51(input: ByteBuffer, length: Int, output: ByteBuffer, encoding: PcmGainEncoding,
                surround: Double = 0.35, startPan: Double = 0.0, endPan: Double = 0.0): Boolean {
        if (!callable || !isAvailable || !input.isDirect || !output.isDirect || output.isReadOnly ||
            length < 0 || length > input.remaining() || length.toLong() * 3 > output.remaining()
        ) return false
        val frames = try {
            nativeUpmix51(input, input.position(), length, output, output.position(),
                encoding.nativeId, surround, startPan, endPan)
        } catch (_: UnsatisfiedLinkError) { callable = false; -1 }
        if (frames != length / (encoding.bytesPerSample * 2)) return false
        input.position(input.position() + length)
        output.position(output.position() + length * 3)
        return true
    }

    @JvmStatic private external fun nativeApiVersion(): Int
    @JvmStatic private external fun nativeUpmix51(
        input: ByteBuffer, offset: Int, length: Int, output: ByteBuffer, outputOffset: Int,
        encoding: Int, surround: Double, startPan: Double, endPan: Double,
    ): Int
}
