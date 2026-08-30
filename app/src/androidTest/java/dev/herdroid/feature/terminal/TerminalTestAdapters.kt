package dev.herdroid.feature.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.TerminalAttachmentKey
import dev.herdroid.session.api.TerminalLease

@Composable
internal fun TerminalScreen(
    routeName: String,
    connectionLabel: String,
    sessionName: String?,
    session: SessionState?,
    client: TerminalLease?,
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
    val state = client?.state?.collectAsStateWithLifecycle()?.value
        ?: dev.herdroid.core.model.TerminalState.Attaching
    TerminalScreen(
        routeName = routeName,
        connectionLabel = connectionLabel,
        sessionName = sessionName,
        session = session,
        attachmentKey = client?.testAttachmentKey(),
        terminalState = state,
        frames = client?.frames ?: kotlinx.coroutines.flow.emptyFlow(),
        onSendText = { client?.sendText(it) },
        onSendBytes = { client?.sendBytes(it) },
        onResize = { cols, rows -> client?.resize(cols, rows) },
        onScroll = { direction, lines, source -> client?.scroll(direction, lines, source) },
        onPreviousTab = onPreviousTab,
        onNextTab = onNextTab,
        onOpenSwitcher = onOpenSwitcher,
        switcherOpen = switcherOpen,
        loading = loading,
        modifier = modifier,
        onBack = onBack,
        onFocusTab = onFocusTab,
        onRetry = onRetry,
        onTakeOver = onTakeOver,
    )
}

@Composable
internal fun TerminalSurface(
    client: TerminalLease,
    switcherOpen: Boolean = false,
    restoreKeyboardAfterSwitcher: MutableState<Boolean?>? = null,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onTakeOver: () -> Unit = {},
    topBar: @Composable (Boolean, () -> Unit, () -> Unit) -> Unit,
) {
    val state by client.state.collectAsStateWithLifecycle()
    TerminalSurface(
        attachmentKey = client.testAttachmentKey(),
        state = state,
        frames = client.frames,
        onSendText = client::sendText,
        onSendBytes = client::sendBytes,
        onResize = { cols, rows -> client.resize(cols, rows) },
        onScroll = { direction, lines, source -> client.scroll(direction, lines, source) },
        switcherOpen = switcherOpen,
        restoreKeyboardAfterSwitcher = restoreKeyboardAfterSwitcher,
        modifier = modifier,
        onRetry = onRetry,
        onTakeOver = onTakeOver,
        topBar = topBar,
    )
}

private fun TerminalLease.testAttachmentKey() = TerminalAttachmentKey(
    "test",
    "test",
    0,
    "test",
    System.identityHashCode(this).toLong(),
)

internal fun ComponentActivity.isImeVisible() = ViewCompat.getRootWindowInsets(window.decorView)
    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
