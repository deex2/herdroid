package dev.herdroid.session.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOwnershipTest {
    @Test
    fun `foreground success survives unbind while rejection immediately becomes bound only`() {
        val events = mutableListOf<String>()
        val ownership = ServiceOwnership(
            stopStarted = { events += "stop-started:$it" },
            disconnectBound = { events += "disconnect" },
            stopTerminal = { events += "stop-terminal:$it" },
        )

        ownership.bound()
        ownership.started(1)
        assertTrue(ownership.promoted(1, accepted = true))
        ownership.unbound()
        assertTrue(ownership.foreground)
        assertTrue(events.isEmpty())

        ownership.bound()
        ownership.started(2)
        assertTrue(ownership.promoted(2, accepted = false))
        assertFalse(ownership.foreground)
        assertEquals(listOf("stop-started:2"), events)

        ownership.unbound()
        assertEquals(listOf("stop-started:2", "disconnect"), events)
    }

    @Test
    fun `queued old terminal stop cannot stop a newer explicit start`() {
        val events = mutableListOf<String>()
        val ownership = ServiceOwnership({}, {}, { events += "stop-terminal:$it" })
        ownership.started(41)
        val queuedOldStop = { ownership.terminal(41) }

        ownership.started(42)
        queuedOldStop()

        assertTrue(events.isEmpty())
        assertFalse(ownership.promoted(41, accepted = true))
        ownership.terminal(42)
        assertEquals(listOf("stop-terminal:42"), events)
    }
}
