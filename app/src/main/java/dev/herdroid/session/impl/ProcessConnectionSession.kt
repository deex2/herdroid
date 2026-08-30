package dev.herdroid.session.impl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionOwnershipMode
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.TerminalAttachRequest
import dev.herdroid.session.api.TerminalLease
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.StateFlow

@Singleton
internal class ProcessConnectionSession @Inject constructor(
    @ApplicationContext context: Context,
    private val bridge: ProcessSessionBridge,
) : ConnectionSession, dev.herdroid.session.api.HierarchyCommands by bridge {
    private val context = context.applicationContext
    private val publication = bridge.snapshot
    override val ready: StateFlow<Boolean> = publication.mapState { it.serviceEpoch != null }
    override val state: StateFlow<ConnectionState> = publication.mapState { it.connection }
    override val diagnostics: StateFlow<List<ConnectionDiagnostic>> = publication.mapState { it.diagnostics }
    override val ownership: StateFlow<ConnectionOwnershipMode> = publication.mapState { it.ownership }
    override fun connect(routeId: Long) {
        ConnectionService.requestConnect(context, routeId)
    }

    override fun disconnect() = bridge.disconnect()
    override fun approveTrust(accept: Boolean) = bridge.approveTrust(accept)
    override fun approveHostKeyReset(accept: Boolean) = bridge.approveHostKeyReset(accept)
    override fun approveInstall(accept: Boolean) = bridge.approveInstall(accept)

    override suspend fun attachTerminal(request: TerminalAttachRequest): TerminalLease? = bridge.attachTerminal(
        request.sessionId,
        request.paneId,
        request.cols,
        request.rows,
        request.takeover,
    )
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> =
    object : StateFlow<R> {
        override val value: R get() = transform(this@mapState.value)
        override val replayCache: List<R> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            var initialized = false
            var previous: Any? = null
            this@mapState.collect {
                val next = transform(it)
                if (!initialized || next != previous) {
                    initialized = true
                    previous = next
                    collector.emit(next)
                }
            }
        }
    }
