package dev.herdroid.session.impl

import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionOwnershipMode
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.HierarchyCommands
import dev.herdroid.session.api.TerminalLease
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@JvmInline
value class ServiceEpoch(val value: Long)

data class SessionPublication(
    val serviceEpoch: ServiceEpoch? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val diagnostics: List<ConnectionDiagnostic> = emptyList(),
    val ownership: ConnectionOwnershipMode = ConnectionOwnershipMode.ActivityBound,
)

interface ServiceEndpoint : HierarchyCommands {
    fun registered(registration: ServiceRegistration)
    fun disconnect()
    fun approveTrust(accept: Boolean)
    fun approveHostKeyReset(accept: Boolean)
    fun approveInstall(accept: Boolean)
    suspend fun attachTerminal(
        sessionId: String,
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
    ): TerminalLease?
}

interface ServiceRegistration : AutoCloseable {
    val epoch: ServiceEpoch
    fun publish(publication: SessionPublication)
    override fun close()
}

@Singleton
class ProcessSessionBridge @Inject constructor() : HierarchyCommands {
    private data class Active(
        val epoch: ServiceEpoch,
        val endpoint: ServiceEndpoint,
        val registration: Registration,
    )

    private val monitor = Any()
    private val mutableSnapshot = MutableStateFlow(SessionPublication())
    private var nextEpoch = 0L
    private var active: Active? = null

    val snapshot: StateFlow<SessionPublication> = mutableSnapshot.asStateFlow()

    fun register(endpoint: ServiceEndpoint): ServiceRegistration = synchronized(monitor) {
        active?.takeIf { it.endpoint === endpoint }?.registration ?: run {
            val epoch = ServiceEpoch(++nextEpoch)
            val registration = Registration(epoch)
            active = Active(epoch, endpoint, registration)
            mutableSnapshot.value = SessionPublication(serviceEpoch = epoch)
            registration
        }
    }

    fun disconnect() = dispatchOrNull { it.disconnect() } ?: Unit
    fun approveTrust(accept: Boolean) = dispatchOrNull { it.approveTrust(accept) } ?: Unit
    fun approveHostKeyReset(accept: Boolean) = dispatchOrNull { it.approveHostKeyReset(accept) } ?: Unit
    fun approveInstall(accept: Boolean) = dispatchOrNull { it.approveInstall(accept) } ?: Unit

    suspend fun attachTerminal(
        sessionId: String,
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
    ): TerminalLease? {
        val reserved = reserve() ?: return null
        val lease = reserved.endpoint.attachTerminal(sessionId, paneId, cols, rows, takeover) ?: return null
        if (synchronized(monitor) { active === reserved }) return lease
        lease.close()
        return null
    }

    override suspend fun createWorkspace(
        sessionId: String,
        cwd: String?,
        label: String?,
        env: Map<String, String>,
    ) = endpoint().createWorkspace(sessionId, cwd, label, env)

    override suspend fun focusWorkspace(sessionId: String, workspaceId: String) =
        endpoint().focusWorkspace(sessionId, workspaceId)

    override suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String) =
        endpoint().renameWorkspace(sessionId, workspaceId, label)

    override suspend fun closeWorkspace(sessionId: String, workspaceId: String) =
        endpoint().closeWorkspace(sessionId, workspaceId)

    override suspend fun createTab(
        sessionId: String,
        workspaceId: String?,
        cwd: String?,
        label: String?,
        env: Map<String, String>,
    ) = endpoint().createTab(sessionId, workspaceId, cwd, label, env)

    override suspend fun focusTab(sessionId: String, tabId: String) = endpoint().focusTab(sessionId, tabId)
    override suspend fun renameTab(sessionId: String, tabId: String, label: String) =
        endpoint().renameTab(sessionId, tabId, label)

    override suspend fun closeTab(sessionId: String, tabId: String) = endpoint().closeTab(sessionId, tabId)
    override suspend fun focusPane(sessionId: String, paneId: String) = endpoint().focusPane(sessionId, paneId)

    override suspend fun splitPane(
        sessionId: String,
        paneId: String,
        direction: SplitDirection,
        ratio: Double?,
        cwd: String?,
        env: Map<String, String>,
    ) = endpoint().splitPane(sessionId, paneId, direction, ratio, cwd, env)

    override suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode) =
        endpoint().zoomPane(sessionId, paneId, mode)

    override suspend fun renamePane(sessionId: String, paneId: String, label: String) =
        endpoint().renamePane(sessionId, paneId, label)

    override suspend fun closePane(sessionId: String, paneId: String) = endpoint().closePane(sessionId, paneId)

    private fun reserve(): Active? = synchronized(monitor) { active }
    private fun endpoint() = reserve()?.endpoint ?: error(NOT_READY)
    private inline fun <T> dispatchOrNull(call: (ServiceEndpoint) -> T): T? = reserve()?.endpoint?.let(call)

    private inner class Registration(override val epoch: ServiceEpoch) : ServiceRegistration {
        override fun publish(publication: SessionPublication) = synchronized(monitor) {
            if (active?.registration === this) mutableSnapshot.value = publication.copy(serviceEpoch = epoch)
        }

        override fun close() = synchronized(monitor) {
            if (active?.registration === this) {
                active = null
                mutableSnapshot.value = SessionPublication()
            }
        }
    }

    private companion object {
        const val NOT_READY = "The Herdr connection is not ready."
    }
}
