package dev.herdroid.core.testing

import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionOwnershipMode
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.TerminalAttachRequest
import dev.herdroid.session.api.TerminalLease
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeConnectionSession : ConnectionSession {
    private val mutableReady = MutableStateFlow(false)
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mutableDiagnostics = MutableStateFlow<List<ConnectionDiagnostic>>(emptyList())
    private val mutableOwnership = MutableStateFlow(ConnectionOwnershipMode.ActivityBound)
    private val terminalLeases = mutableListOf<TerminalLease?>()

    override val ready = mutableReady.asStateFlow()
    override val state = mutableState.asStateFlow()
    override val diagnostics = mutableDiagnostics.asStateFlow()
    override val ownership = mutableOwnership.asStateFlow()

    val connectedRouteIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val attachRequests: MutableList<TerminalAttachRequest> = Collections.synchronizedList(mutableListOf())
    val hierarchyCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val trustApprovals: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val hostKeyResetApprovals: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val installApprovals: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val disconnectCalls = AtomicInteger()
    val attachCalls = AtomicInteger()

    fun publishReady(ready: Boolean) {
        mutableReady.value = ready
    }

    fun publishState(state: ConnectionState) {
        mutableState.value = state
    }

    fun publishDiagnostics(diagnostics: List<ConnectionDiagnostic>) {
        mutableDiagnostics.value = diagnostics
    }

    fun publishOwnership(ownership: ConnectionOwnershipMode) {
        mutableOwnership.value = ownership
    }

    fun enqueueTerminalLease(lease: TerminalLease?) {
        synchronized(terminalLeases) { terminalLeases += lease }
    }

    override fun connect(routeId: Long) {
        connectedRouteIds += routeId
    }

    override fun disconnect() {
        disconnectCalls.incrementAndGet()
    }

    override fun approveTrust(accept: Boolean) {
        trustApprovals += accept
    }

    override fun approveHostKeyReset(accept: Boolean) {
        hostKeyResetApprovals += accept
    }

    override fun approveInstall(accept: Boolean) {
        installApprovals += accept
    }

    override suspend fun attachTerminal(request: TerminalAttachRequest): TerminalLease? {
        attachCalls.incrementAndGet()
        attachRequests += request
        return synchronized(terminalLeases) { terminalLeases.removeFirstOrNull() }
    }

    override suspend fun createWorkspace(
        sessionId: String,
        cwd: String?,
        label: String?,
        env: Map<String, String>,
    ) = record("createWorkspace:$sessionId")

    override suspend fun focusWorkspace(sessionId: String, workspaceId: String) =
        record("focusWorkspace:$sessionId:$workspaceId")

    override suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String) =
        record("renameWorkspace:$sessionId:$workspaceId")

    override suspend fun closeWorkspace(sessionId: String, workspaceId: String) =
        record("closeWorkspace:$sessionId:$workspaceId")

    override suspend fun createTab(
        sessionId: String,
        workspaceId: String?,
        cwd: String?,
        label: String?,
        env: Map<String, String>,
    ) = record("createTab:$sessionId:${workspaceId.orEmpty()}")

    override suspend fun focusTab(sessionId: String, tabId: String) = record("focusTab:$sessionId:$tabId")

    override suspend fun renameTab(sessionId: String, tabId: String, label: String) =
        record("renameTab:$sessionId:$tabId")

    override suspend fun closeTab(sessionId: String, tabId: String) = record("closeTab:$sessionId:$tabId")

    override suspend fun focusPane(sessionId: String, paneId: String) = record("focusPane:$sessionId:$paneId")

    override suspend fun splitPane(
        sessionId: String,
        paneId: String,
        direction: SplitDirection,
        ratio: Double?,
        cwd: String?,
        env: Map<String, String>,
    ) = record("splitPane:$sessionId:$paneId:$direction")

    override suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode) =
        record("zoomPane:$sessionId:${paneId.orEmpty()}:$mode")

    override suspend fun renamePane(sessionId: String, paneId: String, label: String) =
        record("renamePane:$sessionId:$paneId")

    override suspend fun closePane(sessionId: String, paneId: String) = record("closePane:$sessionId:$paneId")

    private fun record(call: String): ActionOutcome {
        hierarchyCalls += call
        return ActionOutcome.Succeeded
    }
}
