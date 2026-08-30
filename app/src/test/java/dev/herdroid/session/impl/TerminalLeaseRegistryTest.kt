package dev.herdroid.session.impl

import dev.herdroid.session.api.TerminalLease

import kotlinx.coroutines.Dispatchers
import dev.herdroid.core.herdr.TerminalClient
import dev.herdroid.core.ssh.RemoteProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalLeaseRegistryTest {
    @Test
    fun lateAttachForSealedAttemptClosesWithoutPublishing() = runTest {
        val registry = TerminalLeaseRegistry(backgroundScope)
        val reservation = registry.reserve(AttemptEpoch(4))
        registry.seal(AttemptEpoch(4))
        val client = client()
        try {
            val completed = registry.register(reservation, client.client).lease
            assertNull(completed)
            registry.sealAndDrain(AttemptEpoch(4))
            assertEquals(1, client.closeCount.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun cancelledAttachDoesNotBlockAttemptDrain() = runTest {
        val registry = TerminalLeaseRegistry(backgroundScope)
        val reservation = registry.reserve(AttemptEpoch(5))

        registry.cancel(reservation)
        registry.sealAndDrain(AttemptEpoch(5))
    }

    @Test
    fun retryUsesAFreshAttemptAndRejectsTheOldCompletion() = runTest {
        val registry = TerminalLeaseRegistry(backgroundScope)
        val old = registry.reserve(AttemptEpoch(6))
        registry.seal(AttemptEpoch(6))
        val oldClient = client()
        val current = registry.reserve(AttemptEpoch(7))
        val currentClient = client()
        try {
            assertNull(registry.register(old, oldClient.client).lease)
            registry.sealAndDrain(AttemptEpoch(6))
            val lease = registry.register(current, currentClient.client).lease

            assertEquals(1, oldClient.closeCount.get())
            assertEquals(0, currentClient.closeCount.get())
            lease?.close()
            registry.sealAndDrain(AttemptEpoch(7))
            advanceUntilIdle()
            assertEquals(1, currentClient.closeCount.get())
        } finally {
            oldClient.close()
            currentClient.close()
        }
    }

    @Test
    fun repeatedLeaseReleaseClosesExactlyOnceThroughReleaseScope() = runTest {
        val registry = TerminalLeaseRegistry(backgroundScope)
        val reservation = registry.reserve(AttemptEpoch(8))
        val client = client()
        try {
            val lease = requireNotNull(registry.register(reservation, client.client).lease)

            lease.close()
            lease.close()
            assertEquals(0, client.closeCount.get())
            registry.sealAndDrain(AttemptEpoch(8))
            advanceUntilIdle()

            assertEquals(1, client.closeCount.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun sealWaitsForInFlightAttachThenClosesItsLateClient() = runTest {
        val registry = TerminalLeaseRegistry(backgroundScope)
        val reservation = registry.reserve(AttemptEpoch(9))
        val client = client()
        try {
            val drain = async { registry.sealAndDrain(AttemptEpoch(9)) }
            advanceUntilIdle()
            assertEquals(false, drain.isCompleted)

            assertNull(registry.register(reservation, client.client).lease)
            drain.await()

            assertEquals(1, client.closeCount.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun serviceTeardownRevokesEveryAttempt() = runTest {
        val registry = TerminalLeaseRegistry(backgroundScope)
        val clients = listOf(client(), client())
        try {
            clients.forEachIndexed { index, client ->
                val reservation = registry.reserve(AttemptEpoch(10L + index))
                requireNotNull(registry.register(reservation, client.client).lease)
            }

            registry.sealAllAndDrain()
            advanceUntilIdle()

            assertEquals(listOf(1, 1), clients.map { it.closeCount.get() })
        } finally {
            clients.forEach(Client::close)
        }
    }

    private fun client(): Client = Client(AtomicInteger()).also { holder ->
        holder.output = PipedOutputStream()
        holder.client = TerminalClient.start(
            RemoteProcess(
                stdin = ByteArrayOutputStream(),
                stdout = PipedInputStream(holder.output),
                stderr = ByteArrayInputStream(byteArrayOf()),
                waitFor = { true },
                exitStatus = { null },
                closeCommand = {},
                closeSession = {},
                onClose = { holder.closeCount.incrementAndGet() },
                ioDispatcher = Dispatchers.IO,
            ),
            Dispatchers.IO,
            Dispatchers.Default,
        )
    }

    private class Client(val closeCount: AtomicInteger) {
        lateinit var client: TerminalClient
        lateinit var output: PipedOutputStream

        fun close() {
            client.close()
            output.close()
        }
    }
}
