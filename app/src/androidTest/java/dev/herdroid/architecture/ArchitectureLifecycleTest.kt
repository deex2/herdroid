package dev.herdroid.architecture

import android.os.Bundle
import android.os.Parcel
import android.content.pm.ApplicationInfo
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dev.herdroid.MainActivity
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.data.EditableEndpoint
import dev.herdroid.core.data.EditableRoute
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.data.di.LocalDataAvailabilityModule
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.di.RepositoryModule
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.keyvault.di.KeyVaultModule
import dev.herdroid.core.testing.FakeConnectionSession
import dev.herdroid.core.testing.FakeKeyVault
import dev.herdroid.core.testing.FakeRouteStore
import dev.herdroid.core.testing.testHardwareKeyMetadata
import dev.herdroid.core.testing.testRouteSummary
import dev.herdroid.core.testing.testSessionState
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.impl.SessionModule
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlin.jvm.JvmSuppressWildcards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
class ArchitectureLifecycleTest {
    private val fixture = LifecycleFixture()

    @BindValue
    @JvmField
    internal val routeRepository: RouteRepository = fixture.routes

    @BindValue
    @JvmField
    internal val connectionRouteRepository: ConnectionRouteRepository = fixture.routes

    @BindValue
    @JvmField
    internal val keyVault: KeyVault = fixture.keys

    @BindValue
    @JvmField
    internal val connectionSession: ConnectionSession = fixture.session

    @BindValue
    @JvmField
    internal val localDataAvailability: StateFlow<@JvmSuppressWildcards LocalDataAvailability> = fixture.availability

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetFixture() {
        hilt.inject()
        fixture.reset()
    }

    @Test
    fun activity_recreation_retains_the_editor_and_unsaved_draft() {
        compose.onNodeWithContentDescription("Edit office").performClick()
        compose.onNodeWithText("Edit connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Target password input").assertTextEquals("")
        compose.onNodeWithContentDescription("Jump password input").assertTextEquals("")
        compose.onNodeWithContentDescription("Connection name input").performTextClearance()
        compose.onNodeWithContentDescription("Connection name input").performTextInput("unsaved marker")

        recreateActivity()

        compose.onNodeWithText("Edit connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection name input").assertTextEquals("unsaved marker")
    }

    @Test
    fun activity_recreation_retains_terminal_without_reattaching() {
        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
        compose.onNodeWithContentDescription("Open terminal").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.session.attachCalls.get() == 1 }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
        val attachCountBeforeRecreate = fixture.session.attachCalls.get()

        recreateActivity()

        assertEquals(attachCountBeforeRecreate, fixture.session.attachCalls.get())
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
    }

    @Test
    fun saved_instance_state_excludes_password_and_key_byte_markers() {
        val passwordMarker = "saved-state-password-marker-582f9"
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Target password input").performTextInput(passwordMarker)

        val editorState = savedInstanceStateBytes()
        assertFalse(editorState.containsSubsequence(passwordMarker.encodeToByteArray()))
        assertFalse(editorState.containsSubsequence(passwordMarker.toByteArray(StandardCharsets.UTF_16LE)))
        assertFalse(editorState.containsSubsequence(fixture.keyMarker.encodeToByteArray()))
    }

    @Test
    fun secure_flag_never_clears_while_a_popped_secure_destination_is_visible() {
        val originalFlags = compose.activity.applicationInfo.flags
        compose.runOnIdle {
            compose.activity.applicationInfo.flags = originalFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
        try {
            compose.onNodeWithContentDescription("Edit office").performClick()
            compose.waitUntil(5_000) { secureFlagSet() }
            assertSecureWhilePopping {
                compose.onAllNodesWithContentDescription("Target password input").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithContentDescription("Manage keys").performClick()
            compose.waitUntil(5_000) { secureFlagSet() }
            assertSecureWhilePopping {
                compose.onAllNodesWithText("SSH keys").fetchSemanticsNodes().isNotEmpty()
            }

            fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
            compose.onNodeWithContentDescription("Open terminal").performScrollTo().performClick()
            compose.waitUntil(5_000) { secureFlagSet() }
            assertSecureWhilePopping {
                compose.onAllNodesWithContentDescription("Open hierarchy switcher").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            compose.runOnIdle {
                compose.activity.applicationInfo.flags = originalFlags
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test
    fun unavailable_process_database_shows_the_fail_closed_screen() {
        compose.runOnIdle { fixture.availability.value = LocalDataAvailability.Unavailable }

        compose.onNodeWithText("Encrypted route storage is unavailable.").assertIsDisplayed()
    }

    @Test
    fun hiltTestGraphUsesOneDispatcherForBothProductionQualifiers() {
        val entryPoint = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TestDispatchersEntryPoint::class.java,
        )

        assertSame(entryPoint.testDispatcher(), entryPoint.ioDispatcher())
        assertSame(entryPoint.testDispatcher(), entryPoint.defaultDispatcher())
    }

    private fun recreateActivity() {
        val original = compose.activity
        compose.runOnIdle { original.recreate() }
        compose.waitUntil(5_000) { compose.activity !== original }
        compose.waitForIdle()
    }

    private fun assertSecureWhilePopping(outgoingVisible: () -> Boolean) {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) {
            outgoingVisible().also { visible -> if (visible) assertTrue(secureFlagSet()) }.not()
        }
    }

    private fun secureFlagSet() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

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

    private class LifecycleFixture {
        val routes = FakeRouteStore(listOf(testRouteSummary()))
        val session = FakeConnectionSession()
        val availability = MutableStateFlow<LocalDataAvailability>(LocalDataAvailability.Available)
        val keyMarker = "saved-state-key-marker-c1f74"
        val keys = FakeKeyVault(listOf(savedKey()))

        fun reset() {
            routes.setRoutes(listOf(testRouteSummary()))
            routes.seedEditable(
                EditableRoute(
                    5,
                    "office",
                    EditableEndpoint("host", 22, "me", null, null),
                    EditableEndpoint("jump", 22, "proxy", null, null),
                ),
            )
            session.publishState(ConnectionState.Disconnected)
            availability.value = LocalDataAvailability.Available
            keys.setKeys(listOf(savedKey()))
        }

        private fun savedKey() = testHardwareKeyMetadata(
            authorizedKeyLine = "ecdsa-sha2-nistp256 PUBLIC $keyMarker",
        )
    }

    companion object {
        private fun hierarchy() = testSessionState()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TestDispatchersEntryPoint {
    fun testDispatcher(): TestDispatcher

    @Dispatcher(HerdroidDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher

    @Dispatcher(HerdroidDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher
}

private fun ByteArray.containsSubsequence(marker: ByteArray) =
    marker.isNotEmpty() && (0..size - marker.size).any { start ->
        marker.indices.all { offset -> this[start + offset] == marker[offset] }
    }
