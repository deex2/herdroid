package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.model.agentStatusFromWire
import dev.herdroid.core.herdr.model.decodeSessionSnapshot
import dev.herdroid.core.herdr.model.immutableList
import dev.herdroid.core.herdr.model.immutableMap
import dev.herdroid.core.herdr.model.immutableSet
import dev.herdroid.core.herdr.wire.BridgeSessionDescriptor
import dev.herdroid.core.herdr.wire.ServerMessage
import dev.herdroid.core.model.SessionState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SessionCoordinator internal constructor(
    messages: Flow<ServerMessage>,
    scope: CoroutineScope,
    bootstrap: List<ServerMessage> = emptyList(),
    private val onTransition: (RouteState) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = mutableState.asStateFlow()

    init {
        check(scope.isActive) { "Session coordinator scope is not active" }
        bootstrap.forEach(::accept)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { messages.collect(::accept) }
    }

    constructor(bridge: BridgeClient, scope: CoroutineScope, onTransition: (RouteState) -> Unit = {}) : this(
        bridge.messages,
        scope,
        listOf(bridge.initialHello, ServerMessage.Sessions(bridge.initialSessions)),
        onTransition,
    )

    internal fun accept(message: ServerMessage) {
        when (message) {
            is ServerMessage.Hello -> applyHello(message)
            is ServerMessage.Sessions -> applySessions(message)
            is ServerMessage.Snapshot -> applySnapshot(message)
            is ServerMessage.AgentStatus -> applyAgentStatus(message)
            is ServerMessage.Degraded -> applyDegraded(message)
            else -> Unit
        }
        onTransition(mutableState.value)
    }

    private fun applyHello(message: ServerMessage.Hello) {
        mutableState.update { current ->
            if (current.epoch == message.epoch) current else RouteState(epoch = message.epoch)
        }
    }

    private fun applySessions(message: ServerMessage.Sessions) {
        mutableState.update { route ->
            val epoch = route.epoch ?: return@update route.withDiagnostic("Ignored Sessions before Hello")
            val sessions = message.sessions.filter(BridgeSessionDescriptor::running).associate { descriptor ->
                descriptor.name to (route.sessions[descriptor.name]?.takeIf { it.epoch == epoch }
                    ?: SessionState(epoch, incarnation = incarnations.incrementAndGet()))
            }
            route.copy(sessions = immutableMap(sessions))
        }
    }

    private fun applySnapshot(message: ServerMessage.Snapshot) {
        val hierarchy = try {
            decodeSessionSnapshot(message.snapshot)
        } catch (_: Exception) {
            mutableState.update { it.withDiagnostic("Ignored malformed Snapshot") }
            return
        }
        mutableState.update { route ->
            if (message.epoch != route.epoch) return@update route.withDiagnostic("Ignored old-epoch Snapshot")
            val previous = route.sessions[message.session]
                ?: return@update route.withDiagnostic("Ignored Snapshot for unknown session")
            val session = SessionState(
                epoch = message.epoch,
                incarnation = previous.incarnation,
                baselineGeneration = previous.baselineGeneration + if (message.baseline) 1 else 0,
                workspaces = hierarchy.workspaces,
                tabs = hierarchy.tabs,
                panes = hierarchy.panes,
                focusedWorkspaceId = hierarchy.focusedWorkspaceId,
                focusedTabId = hierarchy.focusedTabId,
                focusedPaneId = hierarchy.focusedPaneId,
                uncoveredAgentPaneIds = if (message.baseline) emptySet() else previous.uncoveredAgentPaneIds,
            )
            route.copy(sessions = immutableMap(route.sessions + (message.session to session)))
        }
    }

    private fun applyAgentStatus(message: ServerMessage.AgentStatus) {
        mutableState.update { route ->
            if (message.epoch != route.epoch) return@update route.withDiagnostic("Ignored old-epoch AgentStatus")
            val session = route.sessions[message.session]
                ?: return@update route.withDiagnostic("Ignored AgentStatus for unknown session")
            val pane = session.panes[message.paneId]
                ?: return@update route.withDiagnostic("Ignored AgentStatus for unknown pane")
            val panes = immutableMap(session.panes + (message.paneId to pane.copy(agentStatus = agentStatusFromWire(message.status))))
            route.copy(sessions = immutableMap(route.sessions + (message.session to session.copy(panes = panes))))
        }
    }

    private fun applyDegraded(message: ServerMessage.Degraded) {
        mutableState.update { route ->
            if (message.epoch != route.epoch) return@update route.withDiagnostic("Ignored old-epoch Degraded")
            val session = route.sessions[message.session]
                ?: return@update route.withDiagnostic("Ignored Degraded for unknown session")
            val updated = session.copy(uncoveredAgentPaneIds = immutableSet(message.uncoveredPaneIds))
            route.copy(sessions = immutableMap(route.sessions + (message.session to updated)))
        }
    }

    private fun RouteState.withDiagnostic(message: String): RouteState =
        copy(diagnostics = immutableList((diagnostics + message).takeLast(MAX_DIAGNOSTICS)))

    companion object {
        const val MAX_DIAGNOSTICS = 32
        private val incarnations = AtomicLong()
    }
}
