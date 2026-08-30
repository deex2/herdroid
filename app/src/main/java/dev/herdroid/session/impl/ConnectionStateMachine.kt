package dev.herdroid.session.impl

import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.model.SessionState
import dev.herdroid.session.api.ConnectStage
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.HostKeyResetPrompt
import dev.herdroid.session.api.HostTrustPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Small state reducer; the service owns the coroutine and resources that perform these transitions. */
class ConnectionStateMachine(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow<List<ConnectionDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<ConnectionDiagnostic>> = mutableDiagnostics.asStateFlow()
    private var routeId: Long? = null
    private var attempt = 0
    private var immediateRetryUsed = false

    fun connect(id: Long) {
        mutableDiagnostics.value = emptyList()
        routeId = id
        attempt = 0
        immediateRetryUsed = false
        mutableState.value = ConnectionState.Connecting(id, ConnectStage.LoadingRoute)
        diagnostic("Connect requested for route $id")
    }

    fun connecting(stage: ConnectStage) = routeId?.let {
        mutableState.value = ConnectionState.Connecting(it, stage)
        diagnostic("Stage: ${stage.diagnosticLabel}")
    }

    fun needsTrust(prompt: HostTrustPrompt) {
        routeId = prompt.routeId
        mutableState.value = ConnectionState.NeedsTrust(prompt)
        diagnostic("Host key approval required: ${prompt.candidate.hop} ${prompt.candidate.hostname}:${prompt.candidate.port} ${prompt.candidate.algorithm} ${prompt.candidate.sha256}")
    }

    fun needsHostKeyReset(prompt: HostKeyResetPrompt) {
        routeId = prompt.routeId
        mutableState.value = ConnectionState.NeedsHostKeyReset(prompt)
        diagnostic("Host key changed: ${prompt.actual.hop} ${prompt.actual.hostname}:${prompt.actual.port} saved=${prompt.expected.sha256} presented=${prompt.actual.sha256}")
    }

    fun needsBridgeApproval(preview: BridgeApproval) {
        mutableState.value = ConnectionState.NeedsBridgeApproval(preview)
        diagnostic("Bridge approval required: ${preview.osLabel}/${preview.architecture} version=${preview.bridgeVersion} sha256=${preview.sha256}")
    }

    fun connected(sessions: Map<String, SessionState>) {
        routeId?.let {
            attempt = 0
            immediateRetryUsed = false
            mutableState.value = ConnectionState.Connected(it, sessions)
            diagnostic("Connected: sessions=${sessions.size}")
        }
    }

    fun fail(code: String, message: String) {
        routeId?.let {
            mutableState.value = ConnectionState.Failed(it, code, message)
            diagnostic("Failed [$code]: $message")
        }
    }

    fun transportLost(reason: String = "Transport lost") {
        val id = routeId ?: return
        attempt += 1
        mutableState.value = ConnectionState.Reconnecting(id, attempt)
        diagnostic("Retry $attempt in ${retryDelaySeconds()}s: $reason")
    }

    fun retryDelaySeconds(): Long = RETRY_SECONDS[(attempt - 1).coerceAtLeast(0).coerceAtMost(RETRY_SECONDS.lastIndex)]

    fun networkAvailable(): Boolean {
        if (routeId == null || mutableState.value !is ConnectionState.Reconnecting || immediateRetryUsed) return false
        immediateRetryUsed = true
        diagnostic("Network available: waking retry")
        return true
    }

    fun disconnect() {
        if (routeId != null) diagnostic("Disconnected")
        routeId = null
        attempt = 0
        immediateRetryUsed = false
        mutableState.value = ConnectionState.Disconnected
    }

    fun shutdown() = disconnect()

    fun diagnostic(message: String) {
        val next = ConnectionDiagnostic(nowMillis(), sanitizeConnectionDiagnostic(message))
        mutableDiagnostics.update { (it + next).takeLast(MAX_DIAGNOSTICS) }
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 100
        val RETRY_SECONDS = longArrayOf(1, 2, 4, 8, 16, 30)
    }
}

private val SECRET_VALUE = Regex(
    """(?i)\b(password|passphrase|private(?:[_ -]?key)?|token|secret|api[_ -]?key|authorization)\b\s*["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)""",
)
private val BEARER_VALUE = Regex("""(?i)\bauthorization\b\s*["']?\s*:\s*bearer\s+[^\s,;"']+""")
private val URI_USER_INFO = Regex("""(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@""")
private val PRIVATE_KEY_BLOCK = Regex(
    """-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

internal fun sanitizeConnectionDiagnostic(message: String): String = PRIVATE_KEY_BLOCK
    .replace(message, "<redacted-private-key>")
    .let { BEARER_VALUE.replace(it, "authorization=<redacted>") }
    .let { URI_USER_INFO.replace(it, "$1<redacted>@") }
    .let { SECRET_VALUE.replace(it) { match -> "${match.groupValues[1]}=<redacted>" } }
    .take(600)

private val ConnectStage.diagnosticLabel: String
    get() = when (this) {
        ConnectStage.LoadingRoute -> "Loading saved route"
        ConnectStage.ConnectingSsh -> "Authenticating and verifying SSH hops"
        ConnectStage.DiscoveringHerdr -> "Discovering Herdr"
        ConnectStage.InstallingBridge -> "Installing Herdroid Bridge"
        ConnectStage.StartingBridge -> "Validating companion and bootstrapping sessions"
    }
