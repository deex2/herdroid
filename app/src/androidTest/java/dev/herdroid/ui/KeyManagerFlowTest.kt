package dev.herdroid.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dev.herdroid.MainActivity
import dev.herdroid.R
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.di.LocalDataAvailabilityModule
import dev.herdroid.core.data.di.RepositoryModule
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.keyvault.di.KeyVaultModule
import dev.herdroid.core.testing.FakeConnectionSession
import dev.herdroid.core.testing.FakeKeyVault
import dev.herdroid.core.testing.FakeRouteStore
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.impl.SessionModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmSuppressWildcards
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.hamcrest.Matchers.allOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
@UninstallModules(
    RepositoryModule::class,
    KeyVaultModule::class,
    LocalDataAvailabilityModule::class,
    SessionModule::class,
)
class KeyManagerFlowTest {
    private val fixture = Fixture()
    private val availability = MutableStateFlow<LocalDataAvailability>(LocalDataAvailability.Available)
    @BindValue @JvmField internal val routeRepository: RouteRepository = fixture.routes
    @BindValue @JvmField internal val connectionRouteRepository: ConnectionRouteRepository = fixture.routes
    @BindValue @JvmField internal val keyVault: KeyVault = fixture.keys
    @BindValue @JvmField internal val connectionSession: ConnectionSession = fixture.session
    @BindValue @JvmField internal val localDataAvailability:
        StateFlow<@JvmSuppressWildcards LocalDataAvailability> = availability
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun reset() = fixture.reset()

    @After fun clearOverrides() {
        fixture.keys.clearCapturedImport()
    }

    @Test
    fun manager_generates_lists_renames_copies_shares_and_blocks_referenced_delete() {
        compose.onNodeWithContentDescription("Manage keys").performClick()
        compose.onNodeWithText("Hardware-backed credentials").assertIsDisplayed()
        compose.onNodeWithText("Private keys stay here").assertIsDisplayed()
        compose.onNodeWithText("available keys").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add hardware key").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithContentDescription("Add hardware key").fetchSemanticsNode().boundsInRoot
        assertTrue("root=$root add=$add", root.bottom - add.bottom >= 24f)
        compose.onNodeWithText("No SSH keys").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add hardware key").performClick()
        compose.onNodeWithContentDescription("Generate key").performClick()
        compose.onNodeWithText("Key name").performTextInput("new key")
        compose.onNodeWithContentDescription("Generate").performClick()

        compose.onNodeWithText("new key").assertIsDisplayed()
        compose.onNodeWithText("ECDSA P-256", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Generated", substring = true).assertIsDisplayed()
        compose.onNodeWithText("SHA256:test1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Key status In use").assertIsDisplayed()
        compose.onNodeWithText("Export private key").assertDoesNotExist()
        compose.onNodeWithText("Copy private key").assertDoesNotExist()

        compose.onNodeWithContentDescription("Copy public key for new key").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(PUBLIC_LINE, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }

        withIntents {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(
                android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null),
            )
            compose.onNodeWithContentDescription("Share public key for new key").performScrollTo().performClick()
            Intents.intended(
                allOf(
                    hasAction(Intent.ACTION_CHOOSER),
                    hasExtra(
                        Intent.EXTRA_INTENT,
                        allOf(hasAction(Intent.ACTION_SEND), hasType("text/plain"), hasExtra(Intent.EXTRA_TEXT, PUBLIC_LINE)),
                    ),
                ),
            )
        }

        compose.onNodeWithContentDescription("Key actions for new key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Rename new key").performClick()
        compose.onNodeWithText("Key name").performTextClearance()
        compose.onNodeWithText("Key name").performTextInput("laptop")
        compose.onNodeWithContentDescription("Rename").performClick()
        compose.onNodeWithText("laptop").assertIsDisplayed()

        compose.onNodeWithContentDescription("Key actions for laptop").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete laptop").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("Confirm delete").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithContentDescription("Confirm delete").performClick()
        compose.onNodeWithText("Key is used by: alpha, zeta").assertIsDisplayed()
    }

    @Test
    fun manager_imports_successful_native_document_uri_and_warns_about_reset() {
        compose.onNodeWithContentDescription("Manage keys").performClick()
        compose.onNodeWithContentDescription("Add hardware key").performClick()
        compose.onNodeWithText(
            "Uninstalling, clearing app data, or resetting the database destroys generated keys. " +
                "Users should authorize another key on remote accounts before removing their only working Herdroid key.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription("Import key").performClick()
        compose.onNodeWithText("Key name").performTextInput("imported")
        compose.onNodeWithText("Passphrase (optional)").performTextInput("temporary")

        withDocumentPicker {
            compose.onNodeWithContentDescription("Choose key document").performClick()
            Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            compose.onNodeWithContentDescription("Import key").performClick()
            compose.waitUntil(5_000) { fixture.keys.importedDocumentCopy != null }
            compose.onNodeWithText("imported").assertIsDisplayed()
            compose.onNodeWithText("Imported", substring = true).assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(fixture.keys.importedDocumentCopy!!.isNotEmpty())
                assertEquals("temporary", fixture.keys.importedPassphraseCopy?.concatToString())
                assertTrue(fixture.keys.importedDocument!!.all { it == 0.toByte() })
                assertTrue(fixture.keys.importedPassphrase!!.all { it == '\u0000' })
            }
        }
    }

    @Test
    fun selected_import_document_and_draft_survive_activity_recreation() {
        compose.onNodeWithContentDescription("Manage keys").performClick()
        compose.onNodeWithContentDescription("Add hardware key").performClick()
        compose.onNodeWithContentDescription("Import key").performClick()
        compose.onNodeWithText("Key name").performTextInput("rotation import")
        compose.onNodeWithText("Passphrase (optional)").performTextInput("rotation secret")

        withDocumentPicker {
            compose.onNodeWithContentDescription("Choose key document").performClick()
            compose.onNode(hasContentDescription("Selected key document", substring = true)).assertIsDisplayed()

            val original = compose.activity
            compose.runOnIdle { original.recreate() }
            compose.waitUntil(5_000) { compose.activity !== original }

            compose.onNodeWithText("Key name").assertTextEquals("Key name", "rotation import")
            compose.onNodeWithText("Passphrase (optional)")
                .assertTextEquals("Passphrase (optional)", "•••••••••••••••")
            compose.onNode(hasContentDescription("Selected key document", substring = true)).assertIsDisplayed()

            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Add hardware key").performClick()
            compose.onNodeWithContentDescription("Import key").performClick()
            compose.onNodeWithText("Key name").assertTextEquals("Key name", "")
            compose.onNodeWithText("Passphrase (optional)").assertTextEquals("Passphrase (optional)", "")
            compose.onNodeWithContentDescription("Choose key document").assertIsDisplayed()
            compose.onNode(hasContentDescription("Selected key document", substring = true)).assertDoesNotExist()
        }
    }

    @Test
    fun import_draft_markers_are_absent_from_saved_instance_state() {
        compose.onNodeWithContentDescription("Manage keys").performClick()
        compose.onNodeWithContentDescription("Add hardware key").performClick()
        compose.onNodeWithContentDescription("Import key").performClick()
        compose.onNodeWithText("Key name").performTextInput("saved import marker")
        compose.onNodeWithText("Passphrase (optional)").performTextInput("saved passphrase marker")

        withDocumentPicker { documentUri ->
            compose.onNodeWithContentDescription("Choose key document").performClick()
            compose.onNode(hasContentDescription("Selected key document", substring = true)).assertIsDisplayed()

            val savedState = savedInstanceStateBytes()
            assertTrue(!savedState.containsSubsequence("saved import marker".encodeToByteArray()))
            assertTrue(!savedState.containsSubsequence("saved passphrase marker".encodeToByteArray()))
            assertTrue(!savedState.containsSubsequence(documentUri.toString().encodeToByteArray()))
        }
    }

    private inline fun withIntents(block: () -> Unit) {
        Intents.init()
        try {
            block()
        } finally {
            Intents.release()
        }
    }

    private inline fun withDocumentPicker(block: (Uri) -> Unit) = withIntents {
        val uri = Uri.parse("android.resource://${compose.activity.packageName}/${R.xml.data_extraction_rules}")
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            android.app.Instrumentation.ActivityResult(
                android.app.Activity.RESULT_OK,
                Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            ),
        )
        block(uri)
    }

    private fun savedInstanceStateBytes(): ByteArray {
        val state = Bundle()
        compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun endpoint_key_manager_survives_stop_start_with_one_key_collector() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Create key for Target").performScrollTo().performClick()
        compose.onNodeWithText("SSH keys").assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithText("SSH keys").assertIsDisplayed()
        compose.waitUntil(5_000) { fixture.keys.activeCollectors.get() == 1 }
        compose.onNodeWithContentDescription("Back to connection").performClick()
        compose.onNodeWithText("Add connection").assertIsDisplayed()
        assertTrue(fixture.keys.maximumCollectors.get() == 1)
    }

    private class Fixture {
        val routes = FakeRouteStore()
        val session = FakeConnectionSession()
        val keys = FakeKeyVault(generatedRouteUseCount = 2)

        fun reset() {
            keys.clearCapturedImport()
            keys.setKeys(emptyList())
            keys.setReferences(1, listOf("alpha", "zeta"))
        }
    }

    companion object {
        private const val PUBLIC_LINE = "ecdsa-sha2-nistp256 TEST1 herdroid:new key"
    }
}

private fun ByteArray.containsSubsequence(marker: ByteArray) =
    marker.isNotEmpty() && (0..size - marker.size).any { start ->
        marker.indices.all { offset -> this[start + offset] == marker[offset] }
    }
