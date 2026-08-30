package dev.herdroid.session.api

import kotlinx.coroutines.flow.StateFlow

interface ConnectionSession : ConnectionCommands, HierarchyCommands {
    val ready: StateFlow<Boolean>
    val state: StateFlow<ConnectionState>
    val diagnostics: StateFlow<List<ConnectionDiagnostic>>
    val ownership: StateFlow<ConnectionOwnershipMode>
    suspend fun attachTerminal(request: TerminalAttachRequest): TerminalLease?
}

data class TerminalAttachRequest(
    val sessionId: String,
    val paneId: String,
    val cols: Int,
    val rows: Int,
    val takeover: Boolean,
)
