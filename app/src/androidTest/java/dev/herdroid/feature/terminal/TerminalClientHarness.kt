package dev.herdroid.feature.terminal

import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.session.api.TerminalLease
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal class TerminalClientHarness : TerminalLease {
    val stdin = ByteArrayOutputStream()
    override val state = MutableStateFlow<TerminalState>(TerminalState.Interactive(80, 24, 1))
    private val mutableFrames = MutableSharedFlow<TerminalFrame>(replay = 1)
    override val frames = mutableFrames
    private var frameSequence = 0L

    fun writeInput(bytes: ByteArray) = check(mutableFrames.tryEmit(TerminalFrame(++frameSequence, 80, 24, false, bytes)))

    override fun sendText(text: String) = stdin.write(text.encodeToByteArray())
    override fun sendBytes(bytes: ByteArray) = stdin.write(bytes)
    override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        state.value = TerminalState.Interactive(cols, rows, 1)
    }
    override fun scroll(
        direction: TerminalScrollDirection,
        lines: Int,
        source: TerminalScrollSource,
        column: Int?,
        row: Int?,
        modifiers: Int,
    ) = Unit

    override fun close() { state.value = TerminalState.Closed(null) }
}
