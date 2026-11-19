package com.github.mytv.bv.player.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmGainKernelTest {
    @Test
    fun `gain advances once per frame and preserves stereo balance`() {
        val input = pcm16(1000, -1000, 20000, -20000)
        val output = direct(input.remaining())
        val gain = KotlinPcmGainKernel.process(input, output, PcmGainEncoding.Signed16, 2, 1.0, 2.0, 0.5)
        output.flip()
        assertEquals(2.0, gain)
        assertContentEquals(shortArrayOf(1500, -1500, Short.MAX_VALUE, Short.MIN_VALUE), shorts(output))
        assertEquals(input.limit(), input.position())
    }

    @Test
    fun `only remaining input and output ranges are consumed`() {
        val input = direct(12).apply {
            putShort(2, 1000.toShort())
            putShort(4, (-1000).toShort())
            position(2)
            limit(6)
        }
        val output = direct(12).apply {
            repeat(12) { put(0x55.toByte()) }
            position(4)
            limit(8)
        }
        KotlinPcmGainKernel.process(input, output, PcmGainEncoding.Signed16, 2, 0.5, 0.5, 0.0)
        assertEquals(6, input.position())
        assertEquals(8, output.position())
        output.clear()
        assertEquals(500.toShort(), output.getShort(4))
        assertEquals((-500).toShort(), output.getShort(6))
        assertEquals(0x55.toByte(), output.get(3))
        assertEquals(0x55.toByte(), output.get(8))
    }

    @Test
    fun `unity preserves exact float bits and incomplete frame bytes`() {
        val expected = byteArrayOf(0x45, 0x23, 0xc1.toByte(), 0x7f, 0, 0, 0, 0x80.toByte(), 0x55)
        val input = direct(expected.size).apply { put(expected); flip() }
        val output = direct(expected.size)
        KotlinPcmGainKernel.process(input, output, PcmGainEncoding.Float32, 2, 1.0, 1.0, 0.0)
        output.flip()
        assertContentEquals(expected, ByteArray(output.remaining()).also { output.get(it) })
    }

    @Test
    fun `optional backend safely processes heap buffers through Kotlin`() {
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(3.toShort())
            putShort((-3).toShort())
            flip()
        }
        val output = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        val kernel = OptionalRustPcmGainKernel()
        val gain = kernel.process(input, output, PcmGainEncoding.Signed16, 2, 0.5, 0.5, 0.0)
        output.flip()
        assertEquals(0.5, gain)
        assertEquals("Kotlin", kernel.name)
        assertContentEquals(shortArrayOf(1, -1), shorts(output))
    }

    @Test
    fun `direct buffers use native backend when supplied and advance positions once`() {
        val input = direct(12).apply {
            putShort(2, 1000.toShort())
            putShort(4, (-1000).toShort())
            putShort(6, 20000.toShort())
            putShort(8, (-20000).toShort())
            position(2)
            limit(10)
        }
        val output = direct(16).apply { position(4); limit(12) }
        if (java.lang.Boolean.getBoolean("mybv.requireNativeAudio")) {
            assertEquals(true, NativePcmGain.isAvailable, "Host JNI library must load in this test run")
        }
        val kernel = OptionalRustPcmGainKernel()
        val gain = kernel.process(input, output, PcmGainEncoding.Signed16, 2, 1.0, 2.0, 0.5)
        assertEquals(2.0, gain)
        assertEquals(10, input.position())
        assertEquals(12, output.position())
        assertEquals(if (NativePcmGain.isAvailable) "Rust" else "Kotlin", kernel.name)
        output.position(4)
        assertContentEquals(shortArrayOf(1500, -1500, Short.MAX_VALUE, Short.MIN_VALUE), shorts(output))
    }

    @Test
    fun `invalid configuration fails before consuming buffers`() {
        val input = pcm16(1000, -1000)
        val output = direct(4)
        assertFailsWith<IllegalArgumentException> {
            KotlinPcmGainKernel.process(input, output, PcmGainEncoding.Signed16, 0, 1.0, 2.0, 0.5)
        }
        assertEquals(0, input.position())
        assertEquals(0, output.position())
    }

    private fun direct(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun pcm16(vararg values: Short) = direct(values.size * 2).apply {
        values.forEach(::putShort)
        flip()
    }

    private fun shorts(buffer: ByteBuffer) = ShortArray(buffer.remaining() / 2) { buffer.short }
}
