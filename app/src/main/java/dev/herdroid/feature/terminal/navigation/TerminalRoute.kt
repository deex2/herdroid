package dev.herdroid.feature.terminal.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.core.model.OpenResolution
import dev.herdroid.feature.terminal.HierarchySwitcher
import dev.herdroid.feature.terminal.TerminalScreen
import dev.herdroid.feature.terminal.TerminalViewModel
import dev.herdroid.session.api.ConnectionState
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun TerminalRoute(
    routeName: String,
    connectionState: ConnectionState,
    target: OpenTargetIdentifiers?,
    initialResolution: OpenResolution? = null,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val terminal by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(target, initialResolution) { viewModel.initialize(target, initialResolution) }
    BackHandler(enabled = !terminal.switcherOpen, onBack = onBack)
    val attachmentKey = terminal.attachmentKey
    val attachmentFrames = remember(viewModel, attachmentKey) {
        attachmentKey?.let(viewModel::frames) ?: emptyFlow()
    }
    Box(Modifier.fillMaxSize()) {
        TerminalScreen(
            routeName = routeName,
            connectionLabel = connectionLabel(connectionState),
            sessionName = terminal.selectedSession,
            session = terminal.session,
            attachmentKey = attachmentKey,
            terminalState = terminal.terminalState,
            frames = attachmentFrames,
            onSendText = { text -> attachmentKey?.let { viewModel.sendText(it, text) } },
            onSendBytes = { bytes -> attachmentKey?.let { viewModel.sendBytes(it, bytes) } },
            onResize = { cols, rows -> attachmentKey?.let { viewModel.resize(it, cols, rows) } },
            onScroll = { direction, lines, source -> attachmentKey?.let { viewModel.scroll(it, direction, lines, source) } },
            onPreviousTab = viewModel::previousTab,
            onNextTab = viewModel::nextTab,
            onOpenSwitcher = viewModel::openSwitcher,
            switcherOpen = terminal.switcherOpen,
            loading = terminal.switchingTerminal,
            onBack = onBack,
            onFocusTab = { id -> terminal.selectedSession?.let { viewModel.focusTab(it, id) } },
            onRetry = viewModel::retryTerminal,
            onTakeOver = { viewModel.retryTerminal(takeover = true) },
            modifier = if (terminal.switcherOpen) Modifier.semantics { hideFromAccessibility() } else Modifier,
        )
        if (terminal.switcherOpen) {
            HierarchySwitcher(
                sessions = terminal.sessions,
                selectedSession = terminal.selectedSession,
                pendingClose = terminal.pendingClose,
                message = terminal.message,
                level = terminal.switcherLevel,
                workspaceId = terminal.switcherWorkspaceId,
                tabId = terminal.switcherTabId,
                onSelectSession = viewModel::selectSession,
                onFocusSpace = viewModel::focusSpace,
                onFocusTab = viewModel::focusTab,
                onFocusPane = viewModel::focusPane,
                onCreateSpace = viewModel::createSpace,
                onCreateTab = viewModel::createTab,
                onSplitPane = viewModel::splitPane,
                onRename = viewModel::rename,
                onRequestClose = viewModel::requestClose,
                onZoomPane = viewModel::zoomPane,
                onConfirmClose = { viewModel.confirmClose() },
                onCancelClose = viewModel::cancelClose,
                onDismissMessage = viewModel::clearMessage,
                onDismiss = viewModel::closeSwitcher,
            )
        }
    }
}

private fun connectionLabel(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting"
    is ConnectionState.Failed -> "Disconnected"
    is ConnectionState.NeedsHostKeyReset -> "Host key changed"
    else -> "Connecting"
}
