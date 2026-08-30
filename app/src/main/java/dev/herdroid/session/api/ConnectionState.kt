package dev.herdroid.session.api

import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.SessionState

enum class ConnectStage {
    LoadingRoute,
    ConnectingSsh,
    DiscoveringHerdr,
    InstallingBridge,
    StartingBridge,
}

data class HostTrustPrompt(val routeId: Long, val candidate: HostKeyCandidate)
data class HostKeyResetPrompt(
    val routeId: Long,
    val expected: HostKeyCandidate,
    val actual: HostKeyCandidate,
)

data class ConnectionDiagnostic(val timestampMillis: Long, val message: String)

enum class ConnectionOwnershipMode {
    Foreground,
    ActivityBound,
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val routeId: Long, val stage: ConnectStage) : ConnectionState
    data class NeedsTrust(val prompt: HostTrustPrompt) : ConnectionState
    data class NeedsHostKeyReset(val prompt: HostKeyResetPrompt) : ConnectionState
    data class NeedsBridgeApproval(val preview: BridgeApproval) : ConnectionState
    data class Connected(val routeId: Long, val sessions: Map<String, SessionState>) : ConnectionState
    data class Reconnecting(val routeId: Long, val attempt: Int) : ConnectionState
    data class Failed(val routeId: Long, val code: String, val message: String) : ConnectionState
}
