package com.github.mytv.bv.player.audio

import com.github.mytv.bv.player.entity.StereoUpmixSettings
import java.nio.ByteBuffer

/** Sessions belong to a single audio processor and close on reset/release. */
internal object NativeAdaptiveUpmix {
    val available: Boolean by lazy {
        NativePcmGain.isAvailable && try { nativeApiVersion() == 1 }
        catch (_: UnsatisfiedLinkError) { false }
    }

    fun create(rate: Int, settings: StereoUpmixSettings): Session? {
        if (!available || rate !in 8000..192000) return null
        val handle = try { nativeCreate(rate, settings.centre, settings.surround,
            settings.decorrelation, settings.trimDb) }
        catch (_: UnsatisfiedLinkError) { 0L }
        return if (handle == 0L) null else Session(handle)
    }

    class Session internal constructor(private var handle: Long) : AutoCloseable {
        @Synchronized
        fun process(input: ByteBuffer, length: Int, output: ByteBuffer, encoding: PcmGainEncoding): Boolean {
            if (handle == 0L || !input.isDirect || !output.isDirect || output.isReadOnly ||
                length < 0 || length > input.remaining() || length % (encoding.bytesPerSample * 2) != 0 ||
                length.toLong() * 3 > output.remaining()) return false
            if (length == 0) return true
            val frames = try { nativeProcess(handle, input, input.position(), length,
                output, output.position(), encoding.nativeId) }
            catch (_: UnsatisfiedLinkError) { -1 }
            if (frames != length / (encoding.bytesPerSample * 2)) return false
            input.position(input.position() + length)
            output.position(output.position() + length * 3)
            return true
        }

        @Synchronized
        fun reset(): Boolean = handle != 0L && nativeReset(handle)

        @Synchronized
        override fun close() {
            if (handle != 0L) { nativeClose(handle); handle = 0L }
        }
    }

    @JvmStatic private external fun nativeApiVersion(): Int
    @JvmStatic private external fun nativeCreate(rate: Int, centre: Double, surround: Double,
        decorrelation: Double, trimDb: Double): Long
    @JvmStatic private external fun nativeReset(handle: Long): Boolean
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeProcess(handle: Long, input: ByteBuffer, offset: Int,
        length: Int, output: ByteBuffer, outputOffset: Int, encoding: Int): Int
}
