package dev.herdroid.session.impl

import dev.herdroid.core.data.ConnectionRouteInput
import dev.herdroid.core.data.LocalDataUnavailableException
import dev.herdroid.core.herdr.BridgeTransportException
import dev.herdroid.core.herdr.HerdrActions
import dev.herdroid.core.herdr.TerminalClient
import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.ssh.HostKeyApprovalRequired
import dev.herdroid.core.ssh.HostKeyChangedException
import dev.herdroid.core.ssh.HardwareKeyUnavailableException
import dev.herdroid.core.ssh.SshAuthenticationFailedException
import dev.herdroid.session.api.ConnectStage
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.HostKeyResetPrompt
import dev.herdroid.session.api.HostTrustPrompt
import dev.herdroid.session.api.TerminalLease
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ConnectionRouteAttempt(
    val prepareBridge: suspend () -> ConnectionBridgePlan,
    val close: () -> Unit,
    val startCachedBridge: (suspend () -> ConnectionActiveBridge?)? = null,
)

internal class ConnectionBridgePlan(
    val preview: BridgeApproval,
    val verifyExisting: suspend () -> Boolean,
    val install: suspend () -> Unit,
    val start: suspend () -> ConnectionActiveBridge,
)

internal class ConnectionActiveBridge(
    val collectUntilFailure: suspend (suspend (Map<String, SessionState>) -> Unit) -> Unit,
    val close: () -> Unit,
    val actions: HerdrActions? = null,
    val attachTerminal: (suspend (String, String, Int, Int, Boolean) -> TerminalClient)? = null,
)

private class ActiveAttempt(
    val epoch: AttemptEpoch,
    val bridge: ConnectionActiveBridge,
)

internal class ConnectionDependencies(
    val findRoute: suspend (Long) -> ConnectionRouteInput?,
    val knownHosts: suspend (String, Int) -> List<KnownHostRecord>,
    val updateKnownHost: suspend (KnownHostRecord) -> Unit,
    val deleteKnownHost: suspend (dev.herdroid.core.model.HostKeyCandidate) -> Unit = {},
    val clearBridgeCache: suspend (Long) -> Unit = {},
    val connectRoute: (ConnectionRouteInput, List<KnownHostRecord>, List<KnownHostRecord>) -> ConnectionRouteAttempt,
    val waitForRetry: suspend (Long, ReceiveChannel<Unit>) -> Unit,
    val nowMillis: () -> Long = System::currentTimeMillis,
)

internal class TerminalConnectionFailure(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

internal class ConnectionOwner(
    private val scope: CoroutineScope,
    private val machine: ConnectionStateMachine,
    private val dependencies: ConnectionDependencies,
    private val onTerminal: (Int) -> Unit = {},
    releaseScope: CoroutineScope = scope,
) {
    val state: StateFlow<ConnectionState> get() = machine.state

    private val commands = Mutex()
    private val stateLock = Any()
    private val terminalLeases = TerminalLeaseRegistry(releaseScope)
    private val networkWake = Channel<Unit>(Channel.CONFLATED)
    private var generation = 0L
    private var nextAttemptEpoch = 0L
    private var connectionJob: Job? = null
    private var trustApproval: CompletableDeferred<Boolean>? = null
    private var hostKeyResetApproval: CompletableDeferred<Boolean>? = null
    private var bridgeApproval: CompletableDeferred<Boolean>? = null
    private var activeBridge: ActiveAttempt? = null

    fun herdrActions(): HerdrActions? = synchronized(stateLock) { activeBridge?.bridge?.actions }

    suspend fun attachTerminal(
        session: String,
        pane: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
    ): TerminalLease? {
        terminalLeases.drainReleases()
        val (attach, reservation) = synchronized(stateLock) {
            val attempt = activeBridge ?: return null
            val attach = attempt.bridge.attachTerminal ?: return null
            attach to terminalLeases.reserve(attempt.epoch)
        }
        val client = try {
            attach(session, pane, cols, rows, takeover)
        } catch (failure: Throwable) {
            synchronized(stateLock) { terminalLeases.cancel(reservation) }
            throw failure
        }
        val registration = synchronized(stateLock) { terminalLeases.register(reservation, client) }
        return registration.lease
    }

    fun connect(routeId: Long, terminalToken: Int = 0): Job = scope.launch {
        commands.withLock {
            replaceCurrentLocked()
            val current = synchronized(stateLock) {
                machine.connect(routeId)
                generation
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { connectLoop(routeId, current, terminalToken) }
            synchronized(stateLock) { connectionJob = job }
            job.start()
        }
    }

    fun disconnect(): Job = scope.launch {
        commands.withLock {
            replaceCurrentLocked()
            synchronized(stateLock) { machine.disconnect() }
        }
    }

    fun approveTrust(accept: Boolean) {
        synchronized(stateLock) { trustApproval?.complete(accept) }
    }

    fun approveHostKeyReset(accept: Boolean) {
        synchronized(stateLock) { hostKeyResetApproval?.complete(accept) }
    }

    fun approveBridgeInstall(accept: Boolean) {
        synchronized(stateLock) { bridgeApproval?.complete(accept) }
    }

    fun networkAvailable() {
        synchronized(stateLock) {
            if (machine.networkAvailable()) networkWake.trySend(Unit)
        }
    }

    fun cancel() {
        val owned = synchronized(stateLock) {
            generation += 1
            machine.shutdown()
            val job = connectionJob
            connectionJob = null
            trustApproval?.complete(false)
            hostKeyResetApproval?.complete(false)
            bridgeApproval?.complete(false)
            trustApproval = null
            hostKeyResetApproval = null
            bridgeApproval = null
            activeBridge = null
            terminalLeases.sealAll()
            job
        }
        owned?.cancel()
        networkWake.trySend(Unit)
    }

    suspend fun shutdown() {
        commands.withLock {
            replaceCurrentLocked()
            terminalLeases.sealAllAndDrain()
            synchronized(stateLock) { machine.shutdown() }
        }
    }

    suspend fun release() {
        commands.withLock {
            replaceCurrentLocked()
            terminalLeases.sealAllAndDrain()
        }
    }

    private suspend fun replaceCurrentLocked() {
        val owned = synchronized(stateLock) {
            generation += 1
            val job = connectionJob
            connectionJob = null
            trustApproval?.complete(false)
            hostKeyResetApproval?.complete(false)
            bridgeApproval?.complete(false)
            trustApproval = null
            hostKeyResetApproval = null
            bridgeApproval = null
            activeBridge = null
            job
        }
        while (networkWake.tryReceive().isSuccess) {
            // Drain stale wakeups before the next owner generation.
        }
        owned?.cancelAndJoin()
    }

    private suspend fun connectLoop(routeId: Long, current: Long, terminalToken: Int) {
        var approvedPreview: BridgeApproval? = null
        while (isCurrent(current)) {
                val attemptEpoch = synchronized(stateLock) { AttemptEpoch(++nextAttemptEpoch) }
                var route: ConnectionRouteAttempt? = null
                var bridge: ConnectionActiveBridge? = null
                var retryDelay: Long? = null
                try {
                    transition(current) { machine.connecting(ConnectStage.LoadingRoute) }
                    val routeInput = dependencies.findRoute(routeId) ?: run {
                        terminal(current, terminalToken, "route_missing", "The selected route no longer exists")
                        return
                    }
                    route = try {
                        transition(current) { machine.connecting(ConnectStage.ConnectingSsh) }
                        val targetKnownHosts = dependencies.knownHosts(routeInput.target.hostname, routeInput.target.port)
                        val jumpKnownHosts = routeInput.jump?.let {
                            dependencies.knownHosts(it.hostname, it.port)
                        }.orEmpty()
                        runInterruptible {
                            dependencies.connectRoute(routeInput, targetKnownHosts, jumpKnownHosts)
                        }
                    } finally {
                        routeInput.close()
                    }
                    bridge = route.startCachedBridge?.let { start ->
                        transition(current) { machine.connecting(ConnectStage.StartingBridge) }
                        start()
                    }
                    if (bridge == null) {
                        transition(current) { machine.connecting(ConnectStage.DiscoveringHerdr) }
                        val plan = route.prepareBridge()
                        if (!plan.verifyExisting()) {
                            if (approvedPreview != plan.preview) {
                                if (!awaitBridgeApproval(current, plan.preview)) {
                                    disconnected(current, terminalToken)
                                    return
                                }
                                approvedPreview = plan.preview
                            }
                            try {
                                transition(current) { machine.connecting(ConnectStage.InstallingBridge) }
                                plan.install()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Throwable) {
                                throw TerminalConnectionFailure(
                                    "bridge_install_failed",
                                    "Bridge installation failed. Retry to review and approve it again.",
                                )
                            }
                        }
                        transition(current) { machine.connecting(ConnectStage.StartingBridge) }
                        bridge = plan.start()
                    }
                    synchronized(stateLock) {
                        if (current == generation) activeBridge = ActiveAttempt(attemptEpoch, bridge)
                    }
                    bridge.collectUntilFailure { sessions ->
                        transition(current) { machine.connected(sessions) }
                    }
                    throw BridgeTransportException("Bridge stdout closed")
                } catch (failure: HostKeyApprovalRequired) {
                    if (!awaitTrustApproval(current, routeId, failure)) {
                        disconnected(current, terminalToken)
                        return
                    }
                    dependencies.updateKnownHost(failure.candidate.toKnownHostRecord(dependencies.nowMillis()))
                } catch (failure: HostKeyChangedException) {
                    if (!awaitHostKeyReset(current, routeId, failure)) {
                        disconnected(current, terminalToken)
                        return
                    }
                    dependencies.deleteKnownHost(failure.rejection.expected)
                    if (failure.rejection.expected.hop == Hop.TARGET) dependencies.clearBridgeCache(routeId)
                } catch (_: LocalDataUnavailableException) {
                    terminal(current, terminalToken, "storage_unavailable", "Route storage is unavailable")
                    return
                } catch (failure: TerminalConnectionFailure) {
                    terminal(current, terminalToken, failure.code, failure.message)
                    return
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    val diagnostic = connectionFailureDiagnostic(failure)
                    if (!transientConnectionFailure(failure)) {
                        transition(current) { machine.diagnostic("Terminal failure: $diagnostic") }
                        terminal(current, terminalToken, "connection_failed", failure.message ?: "Connection failed")
                        return
                    }
                    retryDelay = synchronized(stateLock) {
                        if (current != generation) return@synchronized null
                        machine.transportLost(diagnostic)
                        machine.retryDelaySeconds()
                    }
                } finally {
                    synchronized(stateLock) {
                        if (activeBridge?.bridge === bridge) activeBridge = null
                        terminalLeases.seal(attemptEpoch)
                    }
                    withContext(NonCancellable) {
                        try {
                            terminalLeases.sealAndDrain(attemptEpoch)
                        } finally {
                            try {
                                bridge?.close()
                            } finally {
                                route?.close()
                            }
                        }
                    }
                }
                retryDelay?.let { dependencies.waitForRetry(it, networkWake) }
        }
    }

    private suspend fun awaitTrustApproval(
        current: Long,
        routeId: Long,
        failure: HostKeyApprovalRequired,
    ): Boolean = awaitApproval(current, trust = true) { deferred ->
        trustApproval = deferred
        machine.needsTrust(HostTrustPrompt(routeId, failure.candidate))
    }

    private suspend fun awaitBridgeApproval(current: Long, preview: BridgeApproval): Boolean =
        awaitApproval(current, trust = false) { deferred ->
            bridgeApproval = deferred
            machine.needsBridgeApproval(preview)
        }

    private suspend fun awaitHostKeyReset(
        current: Long,
        routeId: Long,
        failure: HostKeyChangedException,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val active = synchronized(stateLock) {
            if (current != generation) false else true.also {
                hostKeyResetApproval = deferred
                machine.needsHostKeyReset(
                    HostKeyResetPrompt(routeId, failure.rejection.expected, failure.rejection.actual),
                )
            }
        }
        if (!active) throw CancellationException("Connection was replaced")
        return try {
            deferred.await()
        } finally {
            synchronized(stateLock) { if (hostKeyResetApproval === deferred) hostKeyResetApproval = null }
        }
    }

    private suspend fun awaitApproval(
        current: Long,
        trust: Boolean,
        publish: (CompletableDeferred<Boolean>) -> Unit,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val active = synchronized(stateLock) {
            if (current != generation) false else true.also { publish(deferred) }
        }
        if (!active) throw CancellationException("Connection was replaced")
        return try {
            deferred.await()
        } finally {
            synchronized(stateLock) {
                if (trust && trustApproval === deferred) trustApproval = null
                if (!trust && bridgeApproval === deferred) bridgeApproval = null
            }
        }
    }

    private suspend fun terminal(current: Long, terminalToken: Int, code: String, message: String) {
        commands.withLock {
            val active = synchronized(stateLock) {
                if (current != generation) false else true.also { machine.fail(code, message) }
            }
            if (active) onTerminal(terminalToken)
        }
    }

    private suspend fun disconnected(current: Long, terminalToken: Int) {
        commands.withLock {
            val active = synchronized(stateLock) {
                if (current != generation) false else true.also { machine.disconnect() }
            }
            if (active) onTerminal(terminalToken)
        }
    }

    private fun transition(current: Long, block: () -> Unit) {
        synchronized(stateLock) { if (current == generation) block() }
    }

    private fun isCurrent(current: Long) = synchronized(stateLock) { current == generation }
}

internal fun transientConnectionFailure(failure: Throwable): Boolean =
    failure !is HardwareKeyUnavailableException &&
        failure !is SshAuthenticationFailedException &&
        (failure is IOException || failure is BridgeTransportException)

internal fun connectionFailureDiagnostic(failure: Throwable): String = sanitizeConnectionDiagnostic(
    generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
        .take(6)
        .joinToString(" <- ") { current ->
            val name = current.javaClass.simpleName.ifEmpty { current.javaClass.name }
            safeConnectionFailureMessage(current.message)?.let { "$name: $it" } ?: name
        },
)

private fun safeConnectionFailureMessage(message: String?): String? {
    val lower = message?.lowercase() ?: return null
    return when {
        "connection reset" in lower -> "Connection reset"
        "connection refused" in lower -> "Connection refused"
        "timed out" in lower || "timeout" in lower -> "Timed out"
        "no route to host" in lower -> "No route to host"
        "network is unreachable" in lower -> "Network is unreachable"
        "broken pipe" in lower -> "Broken pipe"
        "stdout closed" in lower -> "Bridge output closed"
        "host key" in lower && "changed" in lower -> "Host key changed"
        "host key" in lower && ("reject" in lower || "mismatch" in lower) -> "Host key rejected"
        "authentication" in lower && ("fail" in lower || "reject" in lower) -> "Authentication failed"
        "auth fail" in lower -> "Authentication failed"
        else -> null
    }
}
