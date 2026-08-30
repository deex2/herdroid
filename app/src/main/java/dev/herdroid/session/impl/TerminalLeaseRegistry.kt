package dev.herdroid.session.impl

import dev.herdroid.core.herdr.TerminalClient
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.session.api.TerminalLease
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@JvmInline
value class AttemptEpoch(val value: Long)

internal class TerminalReservation internal constructor(
    val epoch: AttemptEpoch,
    internal val id: Long,
    internal val completed: CompletableDeferred<Unit>,
)

internal class TerminalRegistration(
    val lease: TerminalLease?,
)

internal class TerminalLeaseRegistry(private val releaseScope: CoroutineScope) {
    private class Attempt {
        var sealed = false
        val reservations = mutableMapOf<Long, TerminalReservation>()
        val leases = mutableSetOf<ClientTerminalLease>()
    }

    private val monitor = Any()
    private val attempts = mutableMapOf<AttemptEpoch, Attempt>()
    private val releases = mutableSetOf<Job>()
    private var nextReservation = 0L
    private var sealedAll = false

    fun reserve(epoch: AttemptEpoch): TerminalReservation = synchronized(monitor) {
        val reservation = TerminalReservation(epoch, ++nextReservation, CompletableDeferred())
        attempts.getOrPut(epoch, ::Attempt).also { attempt ->
            if (!sealedAll && !attempt.sealed) attempt.reservations[reservation.id] = reservation
            else reservation.completed.complete(Unit)
        }
        reservation
    }

    fun register(reservation: TerminalReservation, client: TerminalClient): TerminalRegistration {
        var staleRelease: Job? = null
        val lease = synchronized(monitor) {
            val attempt = attempts[reservation.epoch]
            val accepted = !sealedAll && attempt?.sealed == false &&
                attempt.reservations.remove(reservation.id) === reservation
            if (accepted) {
                ClientTerminalLease(reservation.epoch, client, this).also(attempt.leases::add)
            } else {
                staleRelease = enqueueReleaseLocked(client)
                null
            }
        }
        reservation.completed.complete(Unit)
        staleRelease?.start()
        return TerminalRegistration(lease)
    }

    fun cancel(reservation: TerminalReservation) {
        synchronized(monitor) { attempts[reservation.epoch]?.reservations?.remove(reservation.id) }
        reservation.completed.complete(Unit)
    }

    fun seal(epoch: AttemptEpoch) {
        val jobs = synchronized(monitor) {
            attempts.getOrPut(epoch, ::Attempt).run {
                sealed = true
                leases.mapNotNull(ClientTerminalLease::claim).map(::enqueueReleaseLocked).also { leases.clear() }
            }
        }
        jobs.forEach(Job::start)
    }

    suspend fun sealAndDrain(epoch: AttemptEpoch) {
        seal(epoch)
        val reservations = synchronized(monitor) {
            attempts[epoch]?.reservations?.values?.map { it.completed }.orEmpty()
        }
        reservations.forEach { it.await() }
        drainReleases()
        synchronized(monitor) { attempts.remove(epoch) }
    }

    suspend fun sealAllAndDrain() {
        sealAll()
        val reservations = synchronized(monitor) {
            attempts.values.flatMap { attempt -> attempt.reservations.values.map { it.completed } }
        }
        reservations.forEach { it.await() }
        drainReleases()
        synchronized(monitor) {
            attempts.clear()
            sealedAll = false
        }
    }

    /** Prompt revocation only; every call must be paired with [sealAllAndDrain]. */
    fun sealAll() {
        val jobs = synchronized(monitor) {
            sealedAll = true
            attempts.values.flatMap { attempt ->
                attempt.sealed = true
                attempt.leases.mapNotNull(ClientTerminalLease::claim).map(::enqueueReleaseLocked)
                    .also { attempt.leases.clear() }
            }
        }
        jobs.forEach(Job::start)
    }

    private fun release(lease: ClientTerminalLease) {
        val job = synchronized(monitor) {
            val client = lease.claim() ?: return
            attempts[lease.epoch]?.leases?.remove(lease)
            enqueueReleaseLocked(client)
        }
        job.start()
    }

    private fun enqueueReleaseLocked(client: TerminalClient): Job {
        lateinit var job: Job
        job = releaseScope.launch(start = CoroutineStart.LAZY) {
            withContext(NonCancellable) {
                try {
                    client.close()
                } finally {
                    synchronized(monitor) { releases.remove(job) }
                }
            }
        }
        releases += job
        return job
    }

    suspend fun drainReleases() {
        while (true) {
            val pending = synchronized(monitor) { releases.toList() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    private class ClientTerminalLease(
        val epoch: AttemptEpoch,
        client: TerminalClient,
        private val registry: TerminalLeaseRegistry,
    ) : TerminalLease {
        private val open = AtomicBoolean(true)
        @Volatile private var client: TerminalClient? = client
        override val state = client.state
        override val frames = client.frames
        override fun sendText(text: String) = requireClient().sendText(text)
        override fun sendBytes(bytes: ByteArray) = requireClient().sendBytes(bytes)
        override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) =
            requireClient().resize(cols, rows, cellWidthPx, cellHeightPx)
        override fun scroll(
            direction: TerminalScrollDirection,
            lines: Int,
            source: TerminalScrollSource,
            column: Int?,
            row: Int?,
            modifiers: Int,
        ) = requireClient().scroll(direction, lines, source, column, row, modifiers)

        override fun close() = registry.release(this)

        fun claim(): TerminalClient? = if (open.compareAndSet(true, false)) client.also { client = null } else null

        private fun requireClient() = checkNotNull(client) { "Terminal lease is closed" }
    }
}
