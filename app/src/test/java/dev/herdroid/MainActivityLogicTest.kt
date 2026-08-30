package dev.herdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityLogicTest {
    @Test
    fun `notification permission result retains the exact pending route on grant or denial`() {
        val pending = PendingConnection()

        pending.begin(7)
        assertEquals(7L, pending.complete(granted = true))
        assertNull(pending.complete(granted = true))

        pending.begin(9)
        assertEquals(9L, pending.complete(granted = false))
        assertNull(pending.complete(granted = false))
    }

    @Test
    fun `recreated permission result dispatches grant and denial exactly once without binder readiness`() {
        listOf(true to 21L, false to 22L).forEach { (granted, routeId) ->
            val beforeRecreation = PendingConnection().apply { begin(routeId) }
            val afterRecreation = PendingConnection().apply { restore(beforeRecreation.snapshot()) }
            val dispatched = mutableListOf<Long>()

            afterRecreation.complete(granted)?.let(dispatched::add)
            afterRecreation.complete(granted)?.let(dispatched::add)

            assertEquals(listOf(routeId), dispatched)
        }
    }
}
