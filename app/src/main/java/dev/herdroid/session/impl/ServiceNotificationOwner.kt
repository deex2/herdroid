package dev.herdroid.session.impl

import dev.herdroid.core.herdr.BridgeClient
import dev.herdroid.core.herdr.RouteState
import dev.herdroid.core.herdr.SessionCoordinator
import dev.herdroid.session.api.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal interface ServiceNotificationSink {
    fun promote()
    fun disable()
    fun reconcile(state: ConnectionState.Connected)
}

internal data class NotificationLease(val generation: Long)

internal class ServiceNotificationOwner(
    private val sink: ServiceNotificationSink,
    private val setActivityBound: (Boolean) -> Unit,
) {
    private var generation = 0L

    @Synchronized
    fun foregroundResolved(
        lease: NotificationLease,
        accepted: Boolean,
        current: ConnectionState.Connected?,
    ) {
        if (lease.generation != generation) return
        if (accepted) sink.promote() else sink.disable()
        setActivityBound(!accepted)
        if (accepted && current != null) sink.reconcile(current)
    }

    fun replace(scope: CoroutineScope, command: suspend (NotificationLease) -> Unit): Job {
        val lease = disable()
        return scope.launch { command(lease) }
    }

    fun disconnect(
        scope: CoroutineScope,
        cancelNow: () -> Unit = {},
        command: suspend () -> Unit,
    ): Job {
        disable()
        cancelNow()
        return scope.launch { command() }
    }

    fun terminal(scope: CoroutineScope, command: suspend () -> Unit) = disableThenLaunch(scope, command)

    @Synchronized
    fun connected(lease: NotificationLease, state: ConnectionState.Connected) {
        if (lease.generation == generation) sink.reconcile(state)
    }

    fun stopped() {
        disable()
    }

    fun coordinator(bridge: BridgeClient, scope: CoroutineScope, routeId: Long): SessionCoordinator {
        val lease = currentLease()
        return SessionCoordinator(bridge, scope, transition(lease, routeId))
    }

    private fun transition(lease: NotificationLease, routeId: Long) = { route: RouteState ->
        connected(lease, ConnectionState.Connected(routeId, route.sessions))
    }

    private fun disableThenLaunch(scope: CoroutineScope, command: suspend () -> Unit): Job {
        disable()
        return scope.launch { command() }
    }

    @Synchronized
    private fun disable(): NotificationLease {
        generation += 1
        sink.disable()
        setActivityBound(true)
        return NotificationLease(generation)
    }

    @Synchronized
    private fun currentLease() = NotificationLease(generation)
}
