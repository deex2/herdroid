package dev.herdroid.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.TerminalAttachmentKey
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.session.api.HierarchyCommands
import dev.herdroid.core.model.OpenLevel
import dev.herdroid.core.model.OpenResolution
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.api.TerminalAttachRequest
import dev.herdroid.session.api.TerminalLease
import dev.herdroid.session.api.resolve
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

sealed interface HierarchyTarget {
    val session: String
    val id: String
    val label: String

    data class Space(override val session: String, override val id: String, override val label: String) : HierarchyTarget
    data class Tab(override val session: String, override val id: String, override val label: String) : HierarchyTarget
    data class Pane(override val session: String, override val id: String, override val label: String) : HierarchyTarget
}

data class TerminalUiState(
    val sessions: Map<String, SessionState> = emptyMap(),
    val selectedSession: String? = null,
    val terminalState: TerminalState = TerminalState.Attaching,
    val attachmentKey: TerminalAttachmentKey? = null,
    val switcherOpen: Boolean = false,
    val switcherLevel: OpenLevel = OpenLevel.Panes,
    val switcherWorkspaceId: String? = null,
    val switcherTabId: String? = null,
    val pendingClose: HierarchyTarget? = null,
    val message: String? = null,
    val switchingTerminal: Boolean = false,
) {
    val session: SessionState? get() = selectedSession?.let(sessions::get)
}

@HiltViewModel
class TerminalViewModel internal constructor(
    private val state: StateFlow<ConnectionState>,
    private val actions: HierarchyCommands,
    private val attach: suspend (session: String, pane: String, cols: Int, rows: Int, takeover: Boolean) -> TerminalLease?,
    private val scope: CoroutineScope?,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher = ioDispatcher,
) : ViewModel() {
    @Inject constructor(
        session: ConnectionSession,
        @Dispatcher(HerdroidDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @Dispatcher(HerdroidDispatchers.Default) defaultDispatcher: CoroutineDispatcher,
    ) : this(
        session.state,
        session,
        { sessionId, paneId, cols, rows, takeover ->
            session.attachTerminal(TerminalAttachRequest(sessionId, paneId, cols, rows, takeover))
        },
        null,
        ioDispatcher,
        defaultDispatcher,
    )

    private class StaleOpen(val resolution: OpenResolution) : IllegalStateException()
    private data class TerminalKey(val session: String, val epoch: String, val incarnation: Long, val pane: String)
    private data class PendingCreation(val session: String, val before: TerminalKey?)
    private data class ActiveAttachment(val key: TerminalAttachmentKey, val lease: TerminalLease)

    private val mutableUiState = MutableStateFlow(initialState(state.value))
    val uiState: StateFlow<TerminalUiState> = mutableUiState
    private val vmScope: CoroutineScope get() = scope ?: viewModelScope
    private val activeAttachment = MutableStateFlow<ActiveAttachment?>(null)
    private var terminalStateJob: Job? = null
    private var attached: TerminalKey? = null
    private var pendingCreation: PendingCreation? = null
    private var pendingWorkspaceFocus: Pair<String, String>? = null
    private var dismissOnFocus: TerminalKey? = null
    private var closed = false
    private val attachMutex = Mutex()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val terminalWriter = if (ioDispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
        ioDispatcher.limitedParallelism(1)
    } else {
        ioDispatcher
    }
    private var attachGeneration = 0L
    private var switchGeneration = 0L
    private var switchReady: (() -> Boolean)? = null
    private var initialization: Pair<OpenTargetIdentifiers?, OpenResolution?>? = null

    init {
        vmScope.launch {
            state.collect { connection ->
                val sessions = (connection as? ConnectionState.Connected)?.sessions.orEmpty()
                val selected = mutableUiState.value.selectedSession?.takeIf(sessions::containsKey) ?: sessions.keys.firstOrNull()
                mutableUiState.value = mutableUiState.value.copy(
                    sessions = sessions,
                    selectedSession = selected,
                )
                resolvePendingCreation()
                resolvePendingWorkspaceFocus()
                requestAttach()
            }
        }
    }

    fun openSwitcher() {
        mutableUiState.value = mutableUiState.value.copy(
            switcherOpen = true,
            switcherLevel = OpenLevel.Panes,
            switcherWorkspaceId = null,
            switcherTabId = null,
        )
    }
    fun closeSwitcher() { mutableUiState.value = mutableUiState.value.copy(switcherOpen = false) }
    fun clearMessage() { mutableUiState.value = mutableUiState.value.copy(message = null) }

    fun initialize(target: OpenTargetIdentifiers?, initialResolution: OpenResolution? = null) {
        val requested = target to initialResolution
        if (initialization == requested) return
        initialization = requested
        target?.let { openNotification(it, initialResolution ?: it.resolve(state.value)) }
    }

    private fun openNotification(target: OpenTargetIdentifiers, resolution: OpenResolution) {
        if (resolution.level == OpenLevel.Routes) return
        showOpenResolution(resolution)
        if (resolution.level == OpenLevel.Pane) {
            focusPane(requireNotNull(resolution.session), requireNotNull(resolution.paneId), target)
        }
    }

    private fun showOpenResolution(resolution: OpenResolution) {
        mutableUiState.value = mutableUiState.value.copy(
            selectedSession = resolution.session ?: mutableUiState.value.selectedSession,
            switcherOpen = true,
            switcherLevel = resolution.level,
            switcherWorkspaceId = resolution.workspaceId,
            switcherTabId = resolution.tabId,
            message = resolution.message,
        )
    }

    fun selectSession(session: String) {
        if (closed || session !in mutableUiState.value.sessions || session == mutableUiState.value.selectedSession) return
        beginTerminalSwitch { mutableUiState.value.selectedSession == session }
        mutableUiState.value = mutableUiState.value.copy(selectedSession = session)
        requestAttach()
    }

    fun previousTab() = adjacentTab(-1)
    fun nextTab() = adjacentTab(1)

    fun createSpace(session: String) = creationAction(session) { it.createWorkspace(session) }
    fun focusSpace(session: String, id: String): Job {
        return terminalSwitch(ready = {
            mutableUiState.value.sessions[session]?.focusedWorkspaceId == id
        }, afterSuccess = {
            pendingWorkspaceFocus = session to id
            resolvePendingWorkspaceFocus()
        }) { it.focusWorkspace(session, id) }
    }
    fun createTab(session: String, workspace: String) = creationAction(session) { it.createTab(session, workspace) }
    fun focusTab(session: String, id: String): Job {
        return terminalSwitch(ready = {
            mutableUiState.value.sessions[session]?.focusedTabId == id
        }) { it.focusTab(session, id) }
    }
    fun focusPane(session: String, id: String): Job = focusPane(session, id, null)

    private fun focusPane(session: String, id: String, expected: OpenTargetIdentifiers?): Job {
        val target = mutableUiState.value.sessions[session]?.let {
            TerminalKey(session, it.epoch, it.incarnation, id)
        }
        return terminalSwitch(ready = {
            mutableUiState.value.sessions[session]?.focusedPaneId == id
        }, afterSuccess = {
            if (target != null && focusedKey() == target) closeSwitcher() else dismissOnFocus = target
        }) {
            expected?.resolve(state.value)?.takeIf { it.level != OpenLevel.Pane }?.let { throw StaleOpen(it) }
            it.focusPane(session, id)
        }
    }
    fun splitPane(session: String, id: String, direction: SplitDirection) =
        creationAction(session) { it.splitPane(session, id, direction) }
    fun zoomPane(session: String, id: String) = action { it.zoomPane(session, id, ZoomMode.Toggle) }

    fun rename(target: HierarchyTarget, label: String) = action {
        when (target) {
            is HierarchyTarget.Space -> it.renameWorkspace(target.session, target.id, label)
            is HierarchyTarget.Tab -> it.renameTab(target.session, target.id, label)
            is HierarchyTarget.Pane -> it.renamePane(target.session, target.id, label)
        }
    }

    fun requestClose(target: HierarchyTarget) {
        mutableUiState.value = mutableUiState.value.copy(pendingClose = target)
    }

    fun cancelClose() {
        mutableUiState.value = mutableUiState.value.copy(pendingClose = null)
    }

    fun confirmClose(): Job {
        val target = mutableUiState.value.pendingClose ?: return vmScope.launch {}
        mutableUiState.value = mutableUiState.value.copy(pendingClose = null)
        return action {
            when (target) {
                is HierarchyTarget.Space -> it.closeWorkspace(target.session, target.id)
                is HierarchyTarget.Tab -> it.closeTab(target.session, target.id)
                is HierarchyTarget.Pane -> it.closePane(target.session, target.id)
            }
        }
    }

    fun retryTerminal(takeover: Boolean = false) {
        if (closed) return
        attached = null
        requestAttach(takeover)
    }

    fun close() {
        if (closed) return
        closed = true
        attachGeneration++
        attached = null
        terminalStateJob?.cancel()
        terminalStateJob = null
        val previous = activeAttachment.value?.lease
        activeAttachment.value = null
        mutableUiState.value = mutableUiState.value.copy(
            terminalState = TerminalState.Attaching,
            attachmentKey = null,
            switcherOpen = false,
        )
        previous?.close()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun frames(expectedKey: TerminalAttachmentKey): Flow<TerminalFrame> =
        activeAttachment.flatMapLatest { attachment ->
            attachment?.takeIf { it.key == expectedKey }?.lease?.frames ?: emptyFlow()
        }.flowOn(defaultDispatcher)

    fun sendText(expectedKey: TerminalAttachmentKey, text: String) {
        enqueueTerminalCall { leaseFor(expectedKey)?.sendText(text) }
    }
    fun sendBytes(expectedKey: TerminalAttachmentKey, bytes: ByteArray) {
        enqueueTerminalCall { leaseFor(expectedKey)?.sendBytes(bytes) }
    }
    fun resize(expectedKey: TerminalAttachmentKey, cols: Int, rows: Int) {
        enqueueTerminalCall { leaseFor(expectedKey)?.resize(cols, rows) }
    }
    fun scroll(
        expectedKey: TerminalAttachmentKey,
        direction: TerminalScrollDirection,
        lines: Int,
        source: TerminalScrollSource,
    ) {
        enqueueTerminalCall { leaseFor(expectedKey)?.scroll(direction, lines, source) }
    }

    private fun enqueueTerminalCall(call: () -> Unit) {
        vmScope.launch(terminalWriter) {
            try {
                call()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
        }
    }

    private fun leaseFor(expectedKey: TerminalAttachmentKey) =
        activeAttachment.value?.takeIf { it.key == expectedKey }?.lease

    private fun adjacentTab(offset: Int) {
        val ui = mutableUiState.value
        val sessionName = ui.selectedSession ?: return
        val session = ui.session ?: return
        val workspace = session.focusedWorkspaceId ?: return
        val tabs = session.tabs.values.filter { it.workspaceId == workspace }.sortedBy { it.number }
        if (tabs.size < 2) return
        val current = tabs.indexOfFirst { it.tabId == session.focusedTabId }.coerceAtLeast(0)
        val target = tabs[(current + offset).mod(tabs.size)]
        focusTab(sessionName, target.tabId)
    }

    private fun action(
        afterSuccess: () -> Unit = {},
        afterFailure: () -> Unit = {},
        call: suspend (HierarchyCommands) -> ActionOutcome,
    ): Job = vmScope.launch {
        var runAfterFailure = true
        try {
            when (withContext(ioDispatcher) { call(actions) }) {
                ActionOutcome.Succeeded -> {
                    runAfterFailure = false
                    afterSuccess()
                }
                is ActionOutcome.HostConfirmationRequired -> mutableUiState.value = mutableUiState.value.copy(
                    message = "Herdr opened a worktree confirmation prompt on the host. Complete it there; Herdroid did not retry.",
                )
            }
        } catch (failure: CancellationException) {
            runAfterFailure = false
            throw failure
        } catch (failure: StaleOpen) {
            showOpenResolution(failure.resolution)
        } catch (failure: Exception) {
            mutableUiState.value = mutableUiState.value.copy(message = failure.message ?: "Herdr action failed.")
        } finally {
            if (runAfterFailure) afterFailure()
        }
    }

    private fun terminalSwitch(
        ready: () -> Boolean,
        afterSuccess: () -> Unit = {},
        call: suspend (HierarchyCommands) -> ActionOutcome,
    ): Job {
        val alreadyFocused = ready()
        val generation = beginTerminalSwitch(ready)
        return action(
            afterSuccess = {
                afterSuccess()
                if (alreadyFocused) {
                    if (attached == focusedKey()) finishTerminalSwitch(generation) else requestAttach()
                }
            },
            afterFailure = { finishTerminalSwitch(generation, reopenSwitcher = true) },
            call = call,
        )
    }

    private fun beginTerminalSwitch(ready: () -> Boolean): Long {
        val generation = ++switchGeneration
        attachGeneration++
        switchReady = ready
        mutableUiState.value = mutableUiState.value.copy(
            switchingTerminal = true,
            switcherOpen = false,
            message = null,
        )
        return generation
    }

    private fun finishTerminalSwitch(generation: Long = switchGeneration, reopenSwitcher: Boolean = false) {
        if (generation == switchGeneration) {
            switchReady = null
            mutableUiState.value = mutableUiState.value.copy(
                switchingTerminal = false,
                switcherOpen = reopenSwitcher || mutableUiState.value.switcherOpen,
            )
        }
    }

    private fun creationAction(session: String, call: suspend (HierarchyCommands) -> ActionOutcome): Job {
        val pending = PendingCreation(session, focusedKey(session))
        return action(afterSuccess = {
            pendingCreation = pending
            resolvePendingCreation()
            requestAttach()
        }, call = call)
    }

    private fun focusedKey(): TerminalKey? = mutableUiState.value.selectedSession?.let(::focusedKey)

    private fun focusedKey(session: String): TerminalKey? = mutableUiState.value.sessions[session]?.let { state ->
        state.focusedPaneId?.let { TerminalKey(session, state.epoch, state.incarnation, it) }
    }

    private fun resolvePendingCreation() {
        val pending = pendingCreation ?: return
        val current = focusedKey(pending.session)?.takeIf { it != pending.before } ?: return
        pendingCreation = null
        mutableUiState.value = mutableUiState.value.copy(selectedSession = pending.session)
        if (attached == current) closeSwitcher() else dismissOnFocus = current
    }

    private fun resolvePendingWorkspaceFocus() {
        val (session, workspace) = pendingWorkspaceFocus ?: return
        if (mutableUiState.value.sessions[session]?.focusedWorkspaceId != workspace) return
        val target = focusedKey(session) ?: return
        pendingWorkspaceFocus = null
        if (attached == target) closeSwitcher() else dismissOnFocus = target
    }

    private fun requestAttach(takeover: Boolean = false) {
        val key = focusedKey() ?: return detachTerminal()
        if (mutableUiState.value.switchingTerminal && switchReady?.invoke() == false) return
        if (closed || !takeover && attached == key) return
        val generation = ++attachGeneration
        vmScope.launch {
            attachMutex.withLock {
                if (!isCurrentAttach(generation, key)) return@withLock
                val previous = activeAttachment.value?.lease
                var lease: TerminalLease? = null
                var published = false
                try {
                    withContext(ioDispatcher) {
                        lease = attach(key.session, key.pane, 80, 24, takeover)
                    }
                    if (!isCurrentAttach(generation, key)) {
                        return@withLock
                    }
                    val readyLease = lease
                    if (readyLease == null) {
                        finishTerminalSwitch()
                        return@withLock
                    }
                    attached = key
                    switchReady = null
                    val attachmentKey = TerminalAttachmentKey(
                        key.session,
                        key.epoch,
                        key.incarnation,
                        key.pane,
                        generation,
                    )
                    activeAttachment.value = ActiveAttachment(attachmentKey, readyLease)
                    mutableUiState.value = mutableUiState.value.copy(
                        terminalState = readyLease.state.value,
                        attachmentKey = attachmentKey,
                        message = mutableUiState.value.message.takeIf {
                            it == OpenTargetIdentifiers.STALE_MESSAGE
                        },
                        switchingTerminal = false,
                    )
                    terminalStateJob?.cancel()
                    terminalStateJob = vmScope.launch {
                        readyLease.state.collect { terminalState ->
                            if (activeAttachment.value?.lease === readyLease) {
                                mutableUiState.value = mutableUiState.value.copy(terminalState = terminalState)
                            }
                        }
                    }
                    published = true
                    if (previous !== readyLease) previous?.close()
                    if (dismissOnFocus == key) {
                        dismissOnFocus = null
                        closeSwitcher()
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (!isCurrentAttach(generation, key)) return@withLock
                    switchReady = null
                    mutableUiState.value = mutableUiState.value.copy(
                        message = failure.message ?: "Could not attach terminal.",
                        switchingTerminal = false,
                        switcherOpen = true,
                    )
                } finally {
                    if (!published) lease?.close()
                }
            }
        }
    }

    private fun detachTerminal() {
        attachGeneration++
        attached = null
        terminalStateJob?.cancel()
        terminalStateJob = null
        val previous = activeAttachment.value?.lease
        activeAttachment.value = null
        mutableUiState.value = mutableUiState.value.copy(
            terminalState = TerminalState.Attaching,
            attachmentKey = null,
            switchingTerminal = false,
        )
        previous?.close()
    }

    private fun isCurrentAttach(generation: Long, key: TerminalKey) =
        !closed && generation == attachGeneration && focusedKey() == key

    private companion object {
        fun initialState(connection: ConnectionState): TerminalUiState {
            val sessions = (connection as? ConnectionState.Connected)?.sessions.orEmpty()
            return TerminalUiState(sessions = sessions, selectedSession = sessions.keys.firstOrNull())
        }
    }
}

fun agentStatusLabel(status: AgentStatus) = when (status) {
    AgentStatus.Working -> "running"
    AgentStatus.Blocked -> "waiting"
    AgentStatus.Done -> "done"
    AgentStatus.Idle -> "idle"
    AgentStatus.Unknown -> "unknown"
}

fun paneStatusLabel(status: AgentStatus, uncovered: Boolean) =
    agentStatusLabel(status) + if (uncovered) " · reduced live coverage" else ""
