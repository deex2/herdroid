package dev.herdroid.feature.connections

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.testing.FakeKeyVault
import dev.herdroid.core.testing.FakeRouteStore
import dev.herdroid.core.testing.testRouteSummary
import dev.herdroid.core.ui.HerdroidTheme
import dev.herdroid.feature.connections.navigation.ConnectionEditorRoute
import dev.herdroid.session.api.ConnectStage
import dev.herdroid.session.api.ConnectionState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unavailable_storage_fails_closed_in_the_feature_screen() {
        compose.setContent {
            HerdroidTheme {
                ConnectionsScreen(
                    ConnectionsUiState(availability = LocalDataAvailability.Unavailable),
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("Encrypted route storage is unavailable.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add connection").assertDoesNotExist()
    }

    @Test
    fun list_renders_public_state_and_forwards_callbacks() {
        var edited: Long? = null
        compose.setContent {
            HerdroidTheme {
                ConnectionsScreen(
                    state = ConnectionsUiState(
                        availability = LocalDataAvailability.Available,
                        routes = listOf(testRouteSummary()),
                    ),
                    onEdit = { edited = it },
                    onKeys = {},
                    onTerminal = {},
                    onConnect = {},
                    onDisconnect = {},
                    onTrust = {},
                    onHostKeyReset = {},
                    onInstall = {},
                    onRetry = {},
                    onDiagnostics = {},
                    onDismissDiagnostics = {},
                    onDismissOpenMessage = {},
                )
            }
        }
        compose.onNodeWithContentDescription("More actions for office").performClick()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Duplicate").assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()
        assertEquals(5L, edited)
    }

    @Test
    fun connected_route_replaces_connect_with_terminal_and_disconnect_actions() {
        compose.setContent {
            HerdroidTheme {
                ConnectionsScreen(
                    state = ConnectionsUiState(
                        availability = LocalDataAvailability.Available,
                        routes = listOf(testRouteSummary()),
                        connectionState = ConnectionState.Connected(5, emptyMap()),
                    ),
                    onEdit = {},
                    onKeys = {},
                    onTerminal = {},
                    onConnect = {},
                    onDisconnect = {},
                    onTrust = {},
                    onHostKeyReset = {},
                    onInstall = {},
                    onRetry = {},
                    onDiagnostics = {},
                    onDismissDiagnostics = {},
                    onDismissOpenMessage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Open terminal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Disconnect office").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect office").assertDoesNotExist()
    }

    @Test
    fun bridge_install_shows_waiting_progress_and_disconnect() {
        compose.setContent {
            HerdroidTheme {
                ConnectionsScreen(
                    ConnectionsUiState(
                        availability = LocalDataAvailability.Available,
                        connectionState = ConnectionState.Connecting(5, ConnectStage.InstallingBridge),
                        activityBound = false,
                    ),
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("Installing Herdroid Bridge").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Disconnect").assertIsDisplayed()
    }

    @Test
    fun editor_renders_public_state_and_forwards_draft_changes() {
        val editorState = mutableStateOf(ConnectionEditorUiState(draft = RouteDraft()))
        compose.setContent {
            HerdroidTheme {
                ConnectionEditorScreen(
                    state = editorState.value,
                    onDraftChange = { editorState.value = editorState.value.copy(draft = it) },
                    onCreateKey = {},
                    onSave = {},
                    onRequestDelete = {},
                    onDismissDelete = {},
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Connection name input").performTextInput("office")
        compose.onNodeWithContentDescription("Use jump host").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("office", editorState.value.draft?.name)
            assertTrue(editorState.value.draft?.jump != null)
        }
    }

    @Test
    fun editor_disables_save_and_delete_actions_while_a_mutation_owns_the_destination() {
        compose.setContent {
            HerdroidTheme {
                ConnectionEditorScreen(
                    state = ConnectionEditorUiState(
                        draft = RouteDraft(id = 5, name = "office"),
                        loading = true,
                        confirmDelete = true,
                    ),
                    onDraftChange = {},
                    onCreateKey = {},
                    onSave = {},
                    onRequestDelete = {},
                    onDismissDelete = {},
                    onDelete = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Save connection").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete office").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Confirm delete office").assertIsNotEnabled()
    }

    @Test
    fun diagnostic_clip_is_marked_sensitive() {
        val clip = diagnosticClip("sanitized")
        assertEquals("sanitized", clip.getItemAt(0).text.toString())
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        }
    }

    @Test
    fun paused_editor_cancels_pending_key_handoff_before_delayed_pop() {
        val cancellationStarted = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val cancellationFinished = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val viewModel = ConnectionEditorViewModel(
            SavedStateHandle(),
            FakeRouteStore(),
            keysOnlyVault(
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cancellationStarted.complete(Unit)
                            releaseCancellation.await()
                            cancellationFinished.complete(Unit)
                        }
                    }
                },
            ),
            scope,
        )
        val showEditor = mutableStateOf(true)
        val navigations = AtomicInteger()
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.setContent {
                HerdroidTheme {
                    if (showEditor.value) {
                        ConnectionEditorRoute(
                            routeId = null,
                            createdKeyResults = SavedStateHandle(),
                            onBack = {},
                            onCreateKey = { navigations.incrementAndGet() },
                            viewModel = viewModel,
                        )
                    }
                }
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()
            compose.onNodeWithText("Create key for Target").performClick()
            compose.waitUntil(1_000) { cancellationStarted.isCompleted }

            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            releaseCancellation.complete(Unit)
            compose.waitUntil(1_000) { cancellationFinished.isCompleted }
            compose.waitForIdle()

            assertEquals(0, navigations.get())
            compose.runOnIdle { showEditor.value = false }
        } finally {
            releaseCancellation.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun active_editor_completes_normal_key_handoff() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val viewModel = ConnectionEditorViewModel(
            SavedStateHandle(),
            FakeRouteStore(),
            FakeKeyVault(),
            scope,
        )
        val navigations = AtomicInteger()
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.setContent {
                HerdroidTheme {
                    ConnectionEditorRoute(
                        routeId = null,
                        createdKeyResults = SavedStateHandle(),
                        onBack = {},
                        onCreateKey = { navigations.incrementAndGet() },
                        viewModel = viewModel,
                    )
                }
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()
            compose.onNodeWithText("Create key for Target").performClick()
            compose.waitUntil(1_000) { navigations.get() == 1 }

            assertEquals(1, navigations.get())
        } finally {
            scope.cancel()
        }
    }

    private fun keysOnlyVault(keys: Flow<List<HardwareKeyMetadata>>) = object : KeyVault {
        override val keys = keys
        override suspend fun generate(name: String) = error("unused")
        override suspend fun importKey(name: String, document: ByteArray, passphrase: CharArray?) = error("unused")
        override suspend fun rename(id: Long, name: String) = error("unused")
        override suspend fun delete(id: Long) = error("unused")
    }
}
