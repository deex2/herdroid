package dev.herdroid.feature.keys

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.herdroid.core.testing.testHardwareKeyMetadata
import dev.herdroid.core.ui.HerdroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class KeysScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun key_actions_forward_only_the_public_metadata() {
        val key = testHardwareKeyMetadata(name = "phone", routeUseCount = 2)
        var copied = ""
        var shared = ""
        compose.setContent {
            HerdroidTheme {
                KeysScreen(
                    state = KeysUiState(keys = listOf(key)),
                    backLabel = "Back to connections",
                    onBack = {},
                    onAdd = {},
                    onGenerateDialog = {},
                    onGenerate = {},
                    onImportDialog = {},
                    onNameChange = {},
                    onPassphraseChange = {},
                    onChooseDocument = {},
                    onImport = {},
                    onRenameDialog = {},
                    onRename = {},
                    onCopy = { copied = it.authorizedKeyLine },
                    onShare = { shared = it.authorizedKeyLine },
                    onDeleteDialog = {},
                    onDelete = {},
                    onCancelDialog = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Copy public key for phone").performClick()
        compose.onNodeWithContentDescription("Share public key for phone").performClick()
        assertEquals(key.authorizedKeyLine, copied)
        assertEquals(key.authorizedKeyLine, shared)
    }

    @Test
    fun import_dialog_renders_the_selected_document_and_forwards_import() {
        val uri = Uri.parse("content://keys/private")
        val state = mutableStateOf(KeysUiState(dialog = KeyDialog.IMPORT))
        var imports = 0
        compose.setContent {
            HerdroidTheme {
                KeysScreen(
                    state = state.value,
                    backLabel = "Back to connections",
                    onBack = {},
                    onAdd = {},
                    onGenerateDialog = {},
                    onGenerate = {},
                    onImportDialog = {},
                    onNameChange = { state.value = state.value.copy(keyName = it) },
                    onPassphraseChange = { state.value = state.value.copy(passphrase = it) },
                    onChooseDocument = {
                        state.value = state.value.copy(
                            selectedDocumentUri = uri,
                            selectedDocumentName = "private.pem",
                        )
                    },
                    onImport = { imports++ },
                    onRenameDialog = {},
                    onRename = {},
                    onCopy = {},
                    onShare = {},
                    onDeleteDialog = {},
                    onDelete = {},
                    onCancelDialog = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithText("Key name").performTextInput("phone")
        compose.onNodeWithText("Passphrase (optional)").performTextInput("secret")
        compose.onNodeWithContentDescription("Choose key document").performClick()
        compose.onNodeWithContentDescription("Selected key document private.pem").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import key").performClick()
        assertEquals(1, imports)
    }
}
