package com.github.mytv.bv.player.audio

import java.nio.ByteBuffer
import kotlin.test.*

class Eac3FrameInfoTest {
    @Test
    fun `frame duration uses the encoded block count and keeps caller position`() {
        for ((code, samples) in listOf(256, 512, 768, 1536).withIndex()) {
            val packet = frame(code shl 4)
            assertEquals(Eac3FrameInfo(16, samples), Eac3FrameInfo.parse(packet))
            assertEquals(1, packet.position())
        }
        assertEquals(1536, Eac3FrameInfo.parse(frame(0xc0))?.samples)
    }
    @Test
    fun `truncated corrupt and non EAC3 headers cannot advance encoded clock`() {
        assertNull(Eac3FrameInfo.parse(ByteBuffer.allocate(5)))
        assertNull(Eac3FrameInfo.parse(frame(0).apply { put(1, 0) }))
        assertNull(Eac3FrameInfo.parse(frame(0).apply { put(6, 0) }))
        assertNull(Eac3FrameInfo.parse(frame(0).apply { limit(limit() - 1) }))
    }
    private fun frame(flags: Int) = ByteBuffer.allocate(17).apply {
        put(1, 0x0b); put(2, 0x77); put(3, 0); put(4, 7)
        put(5, flags.toByte()); put(6, 0x80.toByte()); position(1)
    }
}
