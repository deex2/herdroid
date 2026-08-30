package dev.herdroid.feature.keys.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.feature.keys.KeysScreen
import dev.herdroid.feature.keys.KeysViewModel

@Composable
fun KeysRoute(
    backLabel: String,
    returnCreatedKey: Boolean,
    onBack: () -> Unit,
    onCreatedKey: (Long) -> Unit,
    viewModel: KeysViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::selectDocument)
    }

    LaunchedEffect(state.createdKeyId) {
        val keyId = state.createdKeyId ?: return@LaunchedEffect
        onCreatedKey(keyId)
        viewModel.consumeCreatedKey()
    }

    KeysScreen(
        state = state,
        backLabel = backLabel,
        onBack = onBack,
        onAdd = viewModel::openAdd,
        onGenerateDialog = viewModel::openGenerate,
        onGenerate = { viewModel.generateKey(returnCreatedKey) },
        onImportDialog = viewModel::openImport,
        onNameChange = viewModel::updateName,
        onPassphraseChange = viewModel::updatePassphrase,
        onChooseDocument = { documentPicker.launch(arrayOf("*/*")) },
        onImport = {
            state.selectedDocumentUri?.let { viewModel.importKey(it, returnCreatedKey) }
        },
        onRenameDialog = viewModel::openRename,
        onRename = viewModel::renameKey,
        onCopy = { key -> copyPublicKey(context, key, viewModel::reportPlatformError) },
        onShare = { key -> sharePublicKey(context, key, viewModel::reportPlatformError) },
        onDeleteDialog = viewModel::openDelete,
        onDelete = viewModel::deleteKey,
        onCancelDialog = viewModel::cancelDialog,
        onClearMessage = viewModel::clearMessage,
    )
}

private fun copyPublicKey(
    context: Context,
    key: HardwareKeyMetadata,
    onError: (String) -> Unit,
) = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", key.authorizedKeyLine))
}.onFailure { onError(it.message ?: "Unable to copy public key") }.let { Unit }

private fun sharePublicKey(
    context: Context,
    key: HardwareKeyMetadata,
    onError: (String) -> Unit,
) = runCatching {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, key.authorizedKeyLine)
    context.startActivity(Intent.createChooser(send, "Share SSH public key"))
}.onFailure { onError(it.message ?: "Unable to share public key") }.let { Unit }
