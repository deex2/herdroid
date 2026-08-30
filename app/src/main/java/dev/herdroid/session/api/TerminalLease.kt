package dev.herdroid.session.api

import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TerminalLease : AutoCloseable {
    val state: StateFlow<TerminalState>
    val frames: Flow<TerminalFrame>
    fun sendText(text: String)
    fun sendBytes(bytes: ByteArray)
    fun resize(cols: Int, rows: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0)
    fun scroll(
        direction: TerminalScrollDirection,
        lines: Int,
        source: TerminalScrollSource,
        column: Int? = null,
        row: Int? = null,
        modifiers: Int = 0,
    )
    override fun close()
}
