package dev.herdroid.feature.connections.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.SavedStateHandle
import dev.herdroid.feature.connections.ConnectionEditorScreen
import dev.herdroid.feature.connections.ConnectionEditorViewModel
import dev.herdroid.feature.connections.ConnectionsScreen
import dev.herdroid.feature.connections.ConnectionsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ConnectionsRoute(
    onEdit: (Long?) -> Unit,
    onDuplicate: (Long) -> Unit,
    onKeys: () -> Unit,
    onTerminal: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionsScreen(
        state = state,
        onEdit = onEdit,
        onKeys = onKeys,
        onTerminal = onTerminal,
        onConnect = onConnect,
        onDisconnect = viewModel::disconnect,
        onTrust = viewModel::approveTrust,
        onHostKeyReset = viewModel::approveHostKeyReset,
        onInstall = viewModel::approveBridgeInstall,
        onRetry = viewModel::connect,
        onDiagnostics = viewModel::showDiagnostics,
        onDismissDiagnostics = viewModel::dismissDiagnostics,
        onDismissOpenMessage = viewModel::clearOpenMessage,
        onDuplicate = onDuplicate,
        onDelete = viewModel::delete,
    )
}

@Composable
fun ConnectionEditorRoute(
    routeId: Long?,
    createdKeyResults: SavedStateHandle,
    onBack: () -> Unit,
    onCreateKey: () -> Unit,
    viewModel: ConnectionEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val routeScope = rememberCoroutineScope()
    val currentOnCreateKey by rememberUpdatedState(onCreateKey)
    var keyHandoff by remember { mutableStateOf<Job?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.resume(routeId, createdKeyResults)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        keyHandoff?.cancel()
        keyHandoff = null
        viewModel.pause()
    }
    ConnectionEditorScreen(
        state = state,
        onDraftChange = viewModel::updateDraft,
        onCreateKey = { target ->
            if (keyHandoff?.isActive != true) {
                keyHandoff = routeScope.launch {
                    if (viewModel.requestCreatedKey(target)) currentOnCreateKey()
                }
            }
        },
        onSave = { viewModel.save(onBack) },
        onRequestDelete = viewModel::requestDelete,
        onDismissDelete = viewModel::dismissDelete,
        onDelete = { viewModel.delete(onBack) },
        onCancel = {
            viewModel.discard()
            onBack()
        },
    )
}
