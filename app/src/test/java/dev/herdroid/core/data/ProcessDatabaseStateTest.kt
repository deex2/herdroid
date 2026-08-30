package dev.herdroid.core.data

import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin
import dev.herdroid.core.data.db.LocalDataUnavailable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessDatabaseStateTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `process open and repository access wait for the injected IO dispatcher`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var openCalls = 0
        val state = ProcessDatabaseState.start(io) {
            openCalls++
            LocalDataUnavailable
        }
        val routeRead = async(io) {
            runCatching { RouteStore(state).routes.first() }.exceptionOrNull()
        }

        assertEquals(LocalDataAvailability.Initializing, state.availability.value)
        assertEquals(0, openCalls)
        assertFalse(routeRead.isCompleted)

        advanceUntilIdle()

        assertEquals(1, openCalls)
        assertEquals(LocalDataAvailability.Unavailable, state.availability.value)
        assertTrue(routeRead.await() is LocalDataUnavailableException)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelled key mutations do not wait for process database initialization`() = runTest {
        val databaseScheduler = TestCoroutineScheduler()
        val state = ProcessDatabaseState.start(StandardTestDispatcher(databaseScheduler)) { LocalDataUnavailable }
        val store = RouteStore(state)
        val publicKey = byteArrayOf(1, 2, 3)
        val input = NewKeyMetadata(
            "cancelled",
            "cancelled",
            publicKey,
            "SHA256:cancelled",
            SshKeyOrigin.GENERATED,
            HardwareSecurityLevel.TEE,
            1,
        )
        val insert = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { input.use { store.insert(it) } }.exceptionOrNull()
        }
        val delete = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { store.delete(1) {} }.exceptionOrNull()
        }

        assertFalse(insert.isCompleted)
        assertFalse(delete.isCompleted)
        try {
            insert.cancel()
            delete.cancel()
            runCurrent()

            assertTrue("insert remained inside NonCancellable initialization", insert.isCompleted)
            assertTrue("delete remained inside NonCancellable initialization", delete.isCompleted)
            assertTrue("the cancelled insert still owned its public-key buffer", publicKey.all { it == 0.toByte() })
        } finally {
            databaseScheduler.advanceUntilIdle()
            advanceUntilIdle()
        }
    }
}
