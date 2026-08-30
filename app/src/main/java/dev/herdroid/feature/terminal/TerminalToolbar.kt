package dev.herdroid.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.VTermKey

class TerminalModifiers : ModifierManager {
    enum class State { OFF, STICKY, LOCKED }

    var ctrlState by mutableStateOf(State.OFF)
        private set
    var altState by mutableStateOf(State.OFF)
        private set

    val mask: Int
        get() = (if (isCtrlActive()) CTRL_MASK else 0) or (if (isAltActive()) ALT_MASK else 0)

    override fun isCtrlActive() = ctrlState != State.OFF
    override fun isAltActive() = altState != State.OFF
    override fun isShiftActive() = false

    override fun clearTransients() {
        if (ctrlState == State.STICKY) ctrlState = State.OFF
        if (altState == State.STICKY) altState = State.OFF
    }

    fun toggleCtrl() {
        ctrlState = ctrlState.next()
    }

    fun toggleAlt() {
        altState = altState.next()
    }

    private fun State.next() = when (this) {
        State.OFF -> State.STICKY
        State.STICKY -> State.LOCKED
        State.LOCKED -> State.OFF
    }

    private companion object {
        const val ALT_MASK = 2
        const val CTRL_MASK = 4
    }
}

@Composable
fun TerminalToolbar(
    emulator: TerminalEmulator,
    modifiers: TerminalModifiers,
    selectionActive: Boolean,
    enabled: Boolean,
    onToggleSelection: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun sendKey(key: Int) {
        emulator.dispatchKey(modifiers.mask, key)
        modifiers.clearTransients()
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "Scrollable terminal keys" },
    ) {
        TerminalButton("Esc", "Escape key", enabled = enabled) { sendKey(VTermKey.ESCAPE) }
        TerminalButton("Tab", "Tab key", enabled = enabled) { sendKey(VTermKey.TAB) }
        ModifierButton("Ctrl", modifiers.ctrlState, enabled, modifiers::toggleCtrl)
        ModifierButton("Alt", modifiers.altState, enabled, modifiers::toggleAlt)
        TerminalButton("Home", "Home key", enabled = enabled) { sendKey(VTermKey.HOME) }
        TerminalButton("End", "End key", enabled = enabled) { sendKey(VTermKey.END) }
        TerminalButton("PgUp", "Remote page up", enabled = enabled, onClick = onPageUp)
        TerminalButton("PgDn", "Remote page down", enabled = enabled, onClick = onPageDown)
        TerminalButton("←", "Left arrow", enabled = enabled) { sendKey(VTermKey.LEFT) }
        TerminalButton("↑", "Up arrow", enabled = enabled) { sendKey(VTermKey.UP) }
        TerminalButton("↓", "Down arrow", enabled = enabled) { sendKey(VTermKey.DOWN) }
        TerminalButton("→", "Right arrow", enabled = enabled) { sendKey(VTermKey.RIGHT) }
        TerminalButton("Paste", "Paste clipboard", enabled = enabled, onClick = onPaste)
        TerminalButton(
            if (selectionActive) "Cancel" else "Select",
            "Toggle terminal selection",
            enabled = enabled,
            onClick = onToggleSelection,
        )
        TerminalButton("Copy", "Copy terminal selection", enabled = enabled, onClick = onCopy)
    }
}

@Composable
private fun RowScope.ModifierButton(
    label: String,
    state: TerminalModifiers.State,
    enabled: Boolean,
    onClick: () -> Unit,
) = TerminalButton(
    label = label,
    description = "$label modifier",
    state = state.name.lowercase(),
    selected = state != TerminalModifiers.State.OFF,
    enabled = enabled,
    onClick = onClick,
)

@Composable
private fun RowScope.TerminalButton(
    label: String,
    description: String,
    state: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                state?.let { stateDescription = it }
            },
        contentPadding = PaddingValues(0.dp),
        colors = if (selected) {
            ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(label, maxLines = 1)
    }
}
