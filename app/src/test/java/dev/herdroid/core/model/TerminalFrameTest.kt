package dev.herdroid.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFrameTest {
    @Test
    fun `bytes cannot be mutated through constructor or accessor arrays`() {
        val source = byteArrayOf(1, 2, 3)
        val frame = TerminalFrame(4, 80, 24, true, source)

        source[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.bytes)
        frame.bytes[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.bytes)
        assertEquals(TerminalFrame(4, 80, 24, true, byteArrayOf(1, 2, 3)), frame)
        assertEquals(TerminalFrame(4, 80, 24, true, byteArrayOf(1, 2, 3)).hashCode(), frame.hashCode())
    }
}
