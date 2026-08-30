package dev.herdroid.feature.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.SshKeyOrigin
import dev.herdroid.core.ui.ActionButton
import dev.herdroid.core.ui.HerdrColors
import dev.herdroid.core.ui.HerdrIconButton
import dev.herdroid.core.ui.HerdrPanel
import dev.herdroid.core.ui.HerdrSectionLabel
import dev.herdroid.core.ui.HerdrStatusChip
import dev.herdroid.core.ui.OutlinedActionButton

@Composable
fun KeysScreen(
    state: KeysUiState,
    backLabel: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onGenerateDialog: () -> Unit,
    onGenerate: () -> Unit,
    onImportDialog: () -> Unit,
    onNameChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onChooseDocument: () -> Unit,
    onImport: () -> Unit,
    onRenameDialog: (HardwareKeyMetadata) -> Unit,
    onRename: (Long) -> Unit,
    onCopy: (HardwareKeyMetadata) -> Unit,
    onShare: (HardwareKeyMetadata) -> Unit,
    onDeleteDialog: (HardwareKeyMetadata) -> Unit,
    onDelete: (Long) -> Unit,
    onCancelDialog: () -> Unit,
    onClearMessage: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            HerdrIconButton("‹", backLabel, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("SSH keys", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Hardware-backed credentials",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HerdrPanel(
            Modifier.fillMaxWidth(),
            padding = PaddingValues(20.dp),
            spacing = 8.dp,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Private keys stay here", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Signing happens inside Android Keystore. Only public keys can leave this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("◆", color = HerdrColors.Mauve, style = MaterialTheme.typography.titleLarge)
            }
        }
        HerdrSectionLabel("Available keys")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.keys.isEmpty()) item { Text("No SSH keys") }
            items(state.keys, key = HardwareKeyMetadata::id) { key ->
                KeyRow(
                    key,
                    onRename = { onRenameDialog(key) },
                    onCopy = { onCopy(key) },
                    onShare = { onShare(key) },
                    onDelete = { onDeleteDialog(key) },
                )
            }
        }
        if (state.loading) Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ActionButton("＋ Add hardware key", "Add hardware key", Modifier.fillMaxWidth(), onClick = onAdd)
    }

    if (state.dialog == KeyDialog.ADD) AlertDialog(
        onDismissRequest = onCancelDialog,
        title = { Text("Add hardware key") },
        text = { Text("Generate a new Android Keystore key or import an existing ECDSA P-256 key.\n\n$RESET_WARNING") },
        confirmButton = { ActionButton("Generate key", onGenerateDialog) },
        dismissButton = { OutlinedActionButton("Import key", onImportDialog) },
    )

    if (state.dialog == KeyDialog.GENERATE) NameDialog(
        title = "Generate key",
        name = state.keyName,
        onName = onNameChange,
        confirm = "Generate",
        onConfirm = onGenerate,
        onCancel = onCancelDialog,
    )
    state.keyId?.let { keyId -> state.keys.singleOrNull { it.id == keyId } }
        ?.takeIf { state.dialog == KeyDialog.RENAME }
        ?.let { key ->
            NameDialog(
                title = "Rename key",
                name = state.keyName,
                onName = onNameChange,
                confirm = "Rename",
                onConfirm = { onRename(key.id) },
                onCancel = onCancelDialog,
            )
        }
    if (state.dialog == KeyDialog.IMPORT) AlertDialog(
        onDismissRequest = onCancelDialog,
        title = { Text("Import key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state.keyName,
                    onNameChange,
                    label = { Text("Key name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    state.passphrase,
                    onPassphraseChange,
                    label = { Text("Passphrase (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                state.selectedDocumentName?.let {
                    Text(
                        "Selected: $it",
                        Modifier.semantics { contentDescription = "Selected key document $it" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (state.selectedDocumentUri == null) ActionButton("Choose key document", onChooseDocument)
            else ActionButton("Import key", onImport)
        },
        dismissButton = { OutlinedActionButton("Cancel", onCancelDialog) },
    )
    state.keyId?.let { keyId -> state.keys.singleOrNull { it.id == keyId } }
        ?.takeIf { state.dialog == KeyDialog.DELETE }
        ?.let { key ->
            AlertDialog(
                onDismissRequest = onCancelDialog,
                title = { Text("Delete ${key.name}?") },
                text = { Text("This removes the hardware-backed private key permanently.") },
                confirmButton = { ActionButton("Confirm delete") { onDelete(key.id) } },
                dismissButton = { OutlinedActionButton("Cancel", onCancelDialog) },
            )
        }
    state.message?.let {
        AlertDialog(
            onDismissRequest = onClearMessage,
            title = { Text("SSH key") },
            text = { Text(it) },
            confirmButton = { ActionButton("OK", onClearMessage) },
        )
    }
}

@Composable
private fun KeyRow(
    key: HardwareKeyMetadata,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var actions by remember { mutableStateOf(false) }
    HerdrPanel(
        Modifier.fillMaxWidth(),
        padding = PaddingValues(14.dp),
        spacing = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { actions = true }
                .semantics { contentDescription = "Key actions for ${key.name}" },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (key.origin == SshKeyOrigin.GENERATED) "Generated" else "Imported"} · ECDSA P-256",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(key.fingerprint, color = HerdrColors.Teal, style = MaterialTheme.typography.bodySmall)
            }
            HerdrStatusChip(
                if (key.routeUseCount == 0) "Available" else "In use",
                Modifier.semantics {
                    contentDescription = "Key status ${if (key.routeUseCount == 0) "Available" else "In use"}"
                },
                if (key.routeUseCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else HerdrColors.Green,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedActionButton("Copy public key", "Copy public key for ${key.name}", Modifier.weight(1f), onCopy)
            if (key.routeUseCount == 0) {
                OutlinedActionButton("Delete", "Delete ${key.name}", onClick = onDelete)
            } else {
                OutlinedActionButton("Share", "Share public key for ${key.name}", onClick = onShare)
            }
        }
    }
    if (actions) AlertDialog(
        onDismissRequest = { actions = false },
        title = { Text(key.name) },
        text = { Text("Rename this key or permanently delete it when no route uses it.") },
        confirmButton = { OutlinedActionButton("Rename", "Rename ${key.name}") { actions = false; onRename() } },
        dismissButton = { OutlinedActionButton("Delete", "Delete ${key.name}") { actions = false; onDelete() } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    name: String,
    onName: (String) -> Unit,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(title) },
    text = { OutlinedTextField(name, onName, label = { Text("Key name") }, singleLine = true) },
    confirmButton = { ActionButton(confirm, onConfirm) },
    dismissButton = { OutlinedActionButton("Cancel", onCancel) },
)

private const val RESET_WARNING =
    "Uninstalling, clearing app data, or resetting the database destroys generated keys. " +
        "Users should authorize another key on remote accounts before removing their only working Herdroid key."
