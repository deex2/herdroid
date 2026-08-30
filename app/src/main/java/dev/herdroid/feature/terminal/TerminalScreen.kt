package dev.herdroid.feature.terminal

import dev.herdroid.core.ui.HerdrColors
import dev.herdroid.core.ui.HerdrIconButton

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.TerminalAttachmentKey
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun TerminalScreen(
    routeName: String,
    connectionLabel: String,
    sessionName: String?,
    session: SessionState?,
    attachmentKey: TerminalAttachmentKey?,
    terminalState: TerminalState = TerminalState.Attaching,
    frames: Flow<TerminalFrame> = emptyFlow(),
    onSendText: (String) -> Unit = {},
    onSendBytes: (ByteArray) -> Unit = {},
    onResize: (Int, Int) -> Unit = { _, _ -> },
    onScroll: (TerminalScrollDirection, Int, TerminalScrollSource) -> Unit = { _, _, _ -> },
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    onOpenSwitcher: () -> Unit,
    switcherOpen: Boolean = false,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onFocusTab: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onTakeOver: () -> Unit = {},
) {
    val workspace = session?.focusedWorkspaceId?.let(session.workspaces::get)
    val urgent = session?.panes?.values.orEmpty().groupingBy { it.agentStatus }.eachCount()
    val urgency = buildList {
        urgent[AgentStatus.Blocked]?.takeIf { it > 0 }?.let { add("$it waiting") }
        urgent[AgentStatus.Done]?.takeIf { it > 0 }?.let { add("$it done") }
    }.joinToString(" · ").ifEmpty { "No blocked or done agents" }
    val restoreKeyboardAfterSwitcher = remember { mutableStateOf<Boolean?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (loading) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (attachmentKey == null) {
                TerminalHeader(
                    routeName = routeName,
                    connectionLabel = connectionLabel,
                    sessionName = sessionName,
                    session = session,
                    workspaceLabel = workspace?.label ?: "No Space",
                    urgency = urgency,
                    onPreviousTab = onPreviousTab,
                    onNextTab = onNextTab,
                    onOpenSwitcher = onOpenSwitcher,
                    onBack = onBack,
                    onFocusTab = onFocusTab,
                )
                Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black)) {
                    if (session?.focusedPaneId == null) {
                        Text("Waiting for a focused pane", color = androidx.compose.ui.graphics.Color.White)
                    } else {
                        TerminalLoading()
                    }
                }
            } else {
                TerminalSurface(
                    attachmentKey = attachmentKey,
                    state = terminalState,
                    frames = frames,
                    onSendText = onSendText,
                    onSendBytes = onSendBytes,
                    onResize = onResize,
                    onScroll = onScroll,
                    switcherOpen = switcherOpen,
                    restoreKeyboardAfterSwitcher = restoreKeyboardAfterSwitcher,
                    modifier = Modifier.weight(1f),
                    onRetry = onRetry,
                    onTakeOver = onTakeOver,
                ) { keyboardVisible, onShowKeyboard, onHideKeyboard ->
                    TerminalHeader(
                        routeName = routeName,
                        connectionLabel = connectionLabel,
                        sessionName = sessionName,
                        session = session,
                        workspaceLabel = workspace?.label ?: "No Space",
                        urgency = urgency,
                        onPreviousTab = onPreviousTab,
                        onNextTab = onNextTab,
                        onOpenSwitcher = onOpenSwitcher,
                        onBack = onBack,
                        onFocusTab = onFocusTab,
                        keyboardVisible = keyboardVisible,
                        onToggleKeyboard = if (keyboardVisible) onHideKeyboard else onShowKeyboard,
                    )
                }
            }
        }
        if (loading) {
            TerminalLoading(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TerminalLoading(modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(androidx.compose.ui.graphics.Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
        Text(
            "Opening terminal…",
            Modifier.padding(top = 8.dp),
            color = androidx.compose.ui.graphics.Color.White,
        )
    }
}

@Composable
private fun TerminalHeader(
    routeName: String,
    connectionLabel: String,
    sessionName: String?,
    session: SessionState?,
    workspaceLabel: String,
    urgency: String,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onBack: (() -> Unit)?,
    onFocusTab: (String) -> Unit,
    keyboardVisible: Boolean? = null,
    onToggleKeyboard: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .semantics { contentDescription = "Tab strip" }
                .horizontalSwipe(onPreviousTab, onNextTab),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { back ->
                TextButton(
                    onClick = back,
                    modifier = Modifier.semantics { contentDescription = "Back to connections" },
                ) {
                    Text("←")
                }
            }
            Text("●", color = HerdrColors.Green)
            Spacer(Modifier.width(6.dp))
            Text(routeName, style = MaterialTheme.typography.titleSmall)
            Text(
                " · $connectionLabel",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            keyboardVisible?.let { visible ->
                HerdrIconButton(
                    label = if (visible) "⌨↓" else "⌨",
                    description = if (visible) "Hide keyboard" else "Show keyboard",
                    onClick = onToggleKeyboard,
                )
            }
        }
        val hierarchyPath = "${sessionName ?: "No session"} › $workspaceLabel"
        val hierarchyLabel = if (urgency == "No blocked or done agents") hierarchyPath else "$hierarchyPath · $urgency"
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenSwitcher,
                modifier = Modifier
                    .weight(0.55f)
                    .semantics { contentDescription = "Open hierarchy switcher" },
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = HerdrColors.Elevated,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    "▦ $hierarchyLabel ⌄",
                    Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
            Box(
                Modifier
                    .weight(0.45f)
                    .semantics { contentDescription = "Scrollable tabs" },
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    session?.tabs?.values
                        ?.filter { it.workspaceId == session.focusedWorkspaceId }
                        ?.sortedBy { it.number }
                        ?.forEach { tab ->
                            TextButton(
                                onClick = { onFocusTab(tab.tabId) },
                                modifier = Modifier.semantics { selected = tab.focused },
                                shape = MaterialTheme.shapes.small,
                                colors = if (tab.focused) {
                                    ButtonDefaults.textButtonColors(
                                        containerColor = HerdrColors.Selected,
                                        contentColor = HerdrColors.Mauve,
                                    )
                                } else {
                                    ButtonDefaults.textButtonColors(
                                        containerColor = HerdrColors.Elevated,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            ) {
                                Text((if (tab.focused) "● " else "") + tab.label + " · " + agentStatusLabel(tab.agentStatus))
                            }
                        }
                }
            }
        }
    }
}

private fun Modifier.horizontalSwipe(previous: () -> Unit, next: () -> Unit) = pointerInput(previous, next) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var distance = 0f
        do {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: break
            distance = change.position.x - down.position.x
        } while (change.pressed)
        if (distance > TAB_SWIPE_THRESHOLD_PX) previous()
        if (distance < -TAB_SWIPE_THRESHOLD_PX) next()
    }
}

private const val TAB_SWIPE_THRESHOLD_PX = 20f
