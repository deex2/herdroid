package dev.herdroid.ui

import dev.herdroid.MainActivity
import android.content.Context
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.graphics.toPixelMap
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.EndpointAuthenticationInput
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteWriteInput
import dev.herdroid.core.data.di.LocalDataAvailabilityModule
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
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.HostTrustPrompt
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.SessionState
import dev.herdroid.session.impl.SessionModule
import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.ui.HerdrColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.jvm.JvmSuppressWildcards

@HiltAndroidTest
@UninstallModules(
    RepositoryModule::class,
    KeyVaultModule::class,
    LocalDataAvailabilityModule::class,
    SessionModule::class,
)
class RouteFlowTest {
    private val fixture = ActivityFixture()
    private val availability = MutableStateFlow<LocalDataAvailability>(LocalDataAvailability.Available)
    @BindValue @JvmField internal val routeRepository: RouteRepository = fixture.routeRepository
    @BindValue @JvmField internal val connectionRouteRepository: ConnectionRouteRepository = fixture.routes
    @BindValue @JvmField internal val keyVault: KeyVault = fixture.keys
    @BindValue @JvmField internal val connectionSession: ConnectionSession = fixture.session
    @BindValue @JvmField internal val localDataAvailability:
        StateFlow<@JvmSuppressWildcards LocalDataAvailability> = availability
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun reset() {
        hilt.inject()
        fixture.reset()
        compose.runOnIdle {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, true)
            compose.activity.window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test
    fun production_routes_use_the_herdr_native_structure() {
        fixture.routes.setRoutes(listOf(testRouteSummary()))

        compose.onNodeWithText("Remote workspaces").assertIsDisplayed()
        compose.onNodeWithText("Systems ready").assertIsDisplayed()
        compose.onNodeWithText("saved connections").assertIsDisplayed()
        compose.onNodeWithText("office").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect office").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Edit office").fetchSemanticsNode()
        compose.onAllNodesWithContentDescription("Delete office").assertCountEquals(0)
        compose.onAllNodesWithText("Diagnostics").assertCountEquals(0)
        compose.onNodeWithText("connections").assertIsDisplayed()
        compose.onNodeWithText("live spaces").assertIsDisplayed()
        compose.onNodeWithText("agent waiting").assertIsDisplayed()
        val title = compose.onNodeWithText("Herdroid").fetchSemanticsNode().boundsInRoot
        val keys = compose.onNodeWithContentDescription("Manage keys").fetchSemanticsNode().boundsInRoot
        assertTrue("title=$title keys=$keys", keys.left > title.right && keys.top < title.bottom)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithContentDescription("Add connection").fetchSemanticsNode().boundsInRoot
        assertTrue("root=$root add=$add", root.bottom - add.bottom >= 24f)
        assertEquals(HerdrColors.Background, compose.onRoot().captureToImage().toPixelMap()[1, 1])
    }

    @Test
    fun connection_editor_matches_the_approved_field_and_routing_structure() {
        compose.onNodeWithContentDescription("Add connection").performClick()

        val label = compose.onNodeWithText("Connection name").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithContentDescription("Connection name input").fetchSemanticsNode().boundsInRoot
        assertTrue("label=$label input=$input", label.bottom < input.top)
        assertTrue("mockup field height=$input", input.height <= 50f)
        val back = compose.onNodeWithContentDescription("Back to connections").fetchSemanticsNode().boundsInRoot
        assertTrue("square back action=$back", kotlin.math.abs(back.width - back.height) <= 1f)

        val target = compose.onNodeWithContentDescription("Target group").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithContentDescription("Target host input").fetchSemanticsNode().boundsInRoot
        val port = compose.onNodeWithContentDescription("Target port input").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "target=$target host=$host",
            host.left >= target.left && host.top >= target.top && host.right <= target.right && host.bottom <= target.bottom,
        )
        assertTrue("host=$host port=$port", host.top == port.top && host.right < port.left)
        compose.onNodeWithText("routing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Jump host").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not used").assertIsDisplayed()
        compose.onNodeWithText("Connect directly to this target.").performScrollTo().assertIsDisplayed()

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithContentDescription("Save connection").fetchSemanticsNode().boundsInRoot
        assertTrue("root=$root save=$save", root.bottom - save.bottom >= 24f)
    }

    @Test
    fun successful_route_save_preserves_passwords_during_save_then_wipes_owned_buffers() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Connection name input").performTextInput("office")
        compose.onNodeWithContentDescription("Target host input").performTextInput("host")
        compose.onNodeWithContentDescription("Target user input").performTextInput("me")
        compose.onNodeWithContentDescription("Target password input").performTextInput("secret")
        compose.onNodeWithContentDescription("Use jump host").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Jump host input").performTextInput("jump")
        compose.onNodeWithContentDescription("Jump user input").performTextInput("proxy")
        compose.onNodeWithContentDescription("Jump password input").performTextInput("jump-secret")

        compose.onNodeWithContentDescription("Save connection").performClick()
        compose.waitUntil(5_000) { fixture.routes.saveCalls.get() == 1 }
        compose.waitForIdle()

        assertEquals(listOf("secret", "jump-secret"), fixture.savedPasswordContents)
        assertEquals(2, fixture.savedPasswordBuffers.size)
        assertTrue(fixture.savedPasswordBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    @Test
    fun production_activity_saves_edits_deletes_and_opens_the_native_key_document_picker() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Connection name input").performTextInput("office")
        compose.onNodeWithContentDescription("Target host input").performTextInput("host")
        compose.onNodeWithContentDescription("Target user input").performTextInput("me")
        compose.onNodeWithContentDescription("Target password input").performTextInput("secret")
        compose.onNodeWithContentDescription("Use jump host").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Jump host input").performTextInput("jump")
        compose.onNodeWithContentDescription("Jump user input").performTextInput("proxy")
        val focusedPassword = compose.onNodeWithContentDescription("Jump password input")
        focusedPassword.performClick()
        focusedPassword.performTextInput("jump-secret")
        compose.runOnIdle {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            compose.activity.window.setLayout(COMPACT_WIDTH, COMPACT_HEIGHT)
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(compose.activity.currentFocus, 0)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.let {
                it.measuredWidth == COMPACT_WIDTH && it.measuredHeight == COMPACT_HEIGHT
            }
        }
        compose.runOnIdle {
            assertEquals(COMPACT_WIDTH, compose.activity.window.decorView.measuredWidth)
            assertEquals(COMPACT_HEIGHT, compose.activity.window.decorView.measuredHeight)
        }
        focusedPassword.assertIsFocused()
        compose.waitUntil(5_000) { compose.activity.isImeVisible() }
        compose.runOnIdle { assertTrue(compose.activity.isImeVisible()) }
        compose.onNodeWithContentDescription("Save connection").performClick()
        assertEquals("office", fixture.routes.routes.value.single().name)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("Connect office").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithContentDescription("Connect office").assertIsDisplayed()
        assertEquals(1, fixture.routes.saveCalls.get())

        compose.onNodeWithContentDescription("Edit office").performScrollTo().performClick()
        val targetPassword = compose.onNodeWithContentDescription("Target password input")
        targetPassword.assertTextEquals("")
        compose.onNodeWithContentDescription("Jump password input").assertTextEquals("")
        targetPassword.performTextInput("updated")
        compose.onNodeWithContentDescription("Jump password input").performTextInput("jump-updated")
        compose.onNodeWithContentDescription("Save connection").performClick()
        compose.waitUntil(5_000) { fixture.routes.saveCalls.get() == 2 }

        compose.onNodeWithContentDescription("Edit office").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Use Target hardware key")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Select phone for Target").performClick()
        compose.onNodeWithContentDescription("Target hardware key phone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Use Jump hardware key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Select phone for Jump").performClick()
        compose.onNodeWithContentDescription("Jump hardware key phone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete office").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete office?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Confirm delete office").performClick()
        compose.onNodeWithText("No connections").assertIsDisplayed()
    }

    @Test
    fun production_activity_shows_and_copies_sanitized_connection_diagnostics() {
        fixture.session.publishDiagnostics(listOf(
            ConnectionDiagnostic(1_000, "Stage: Authenticating and verifying SSH hops"),
            ConnectionDiagnostic(2_000, "Retry 1: ConnectionException: Connection reset"),
        ))

        compose.onNodeWithContentDescription("Diagnostics").performClick()
        compose.onNodeWithText("Connection reset", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy diagnostics").performClick()

        val copied = compose.activity.getSystemService(ClipboardManager::class.java)
        val clip = copied.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(compose.activity).toString()
        assertTrue(text.contains("Authenticating and verifying SSH hops"))
        assertTrue(text.contains("ConnectionException: Connection reset"))
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        }
    }

    @Test
    fun production_activity_survives_configuration_recreation_while_bound() {
        val original = compose.activity
        compose.runOnIdle { original.recreate() }
        compose.waitUntil(5_000) { compose.activity !== original }
        val graceElapsedAt = SystemClock.uptimeMillis() + 750
        compose.waitUntil(2_000) { SystemClock.uptimeMillis() >= graceElapsedAt }

        compose.onNodeWithContentDescription("Add connection").assertIsDisplayed()
    }

    @Test
    fun production_activity_drives_disconnect_cancel_retry_missing_herdr_and_debug_terminal_mirroring() {
        fixture.routes.setRoutes(listOf(testRouteSummary()))
        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
        compose.onNodeWithContentDescription("Disconnect").performClick()
        assertEquals(1, fixture.session.disconnectCalls.get())

        fixture.session.publishState(ConnectionState.NeedsTrust(HostTrustPrompt(5, HostKeyCandidate(Hop.TARGET, "host", 22, "ssh-ed25519", "SHA256:abc", "key"))))
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertFalse(fixture.session.trustApprovals.last())

        fixture.session.publishState(ConnectionState.NeedsBridgeApproval(BridgeApproval("office", RemoteOperatingSystem.LINUX, "x86_64", "linux", "/root", "0.1.0", "0.8.0", "a".repeat(64))))
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertFalse(fixture.session.installApprovals.last())

        fixture.session.publishState(ConnectionState.Failed(5, "herdr_missing", "missing"))
        compose.onNodeWithText("Edit this target and set an absolute Herdr path under Advanced.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry").performClick()
        assertEquals(5L, fixture.session.connectedRouteIds.last())

        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open terminal").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.onNodeWithContentDescription("Back to connections").performClick()
        compose.runOnIdle {
            assertFalse(compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test
    fun terminal_open_waits_for_the_first_hierarchy() {
        fixture.routes.setRoutes(listOf(testRouteSummary()))
        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to SessionState("e1"))))

        compose.onNodeWithContentDescription("Open terminal").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to connections").assertIsDisplayed()
        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))

        compose.waitUntil(5_000) { fixture.terminalAttachments == listOf("work:p1") }
    }

    @Test
    fun connecting_actions_have_elevated_backgrounds() {
        fixture.session.publishState(ConnectionState.Connecting(5, dev.herdroid.session.api.ConnectStage.ConnectingSsh))
        fun buttonColor(description: String): androidx.compose.ui.graphics.Color {
            val buttons = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            val button = buttons.fetchSemanticsNodes().indices.map { buttons[it] }
                .maxBy { it.fetchSemanticsNode().boundsInRoot.top }
                .assertIsDisplayed()
            val image = button.captureToImage().toPixelMap()
            return image[10, image.height / 2]
        }

        assertEquals(HerdrColors.Elevated, buttonColor("Diagnostics"))
        assertEquals(HerdrColors.Elevated, buttonColor("Disconnect"))
    }

    @Test
    fun system_back_returns_from_every_secondary_screen() {
        fixture.routes.setRoutes(listOf(testRouteSummary()))

        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithText("Add connection").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Add connection").assertIsDisplayed()

        compose.onNodeWithContentDescription("Manage keys").performClick()
        compose.onNodeWithText("SSH keys").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Add connection").assertIsDisplayed()

        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
        compose.onNodeWithContentDescription("Open terminal").performClick()
        compose.onNodeWithContentDescription("Back to connections").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Add connection").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open terminal").performClick()
        compose.onNodeWithContentDescription("Open hierarchy switcher").performClick()
        compose.onNodeWithContentDescription("Hierarchy switcher").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
    }

    @Test
    fun every_app_screen_starts_below_the_status_bar() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        }
        compose.waitForIdle()
        val visibleBounds = android.graphics.Rect().also {
            compose.activity.window.decorView.getWindowVisibleDisplayFrame(it)
        }
        val contentLocation = IntArray(2).also {
            compose.activity.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(it)
        }
        fun assertBelowStatusBar(label: String, topInRoot: Float) {
            val topInWindow = contentLocation[1] + topInRoot
            assertTrue("$label top=$topInWindow status bar bottom=${visibleBounds.top}", topInWindow >= visibleBounds.top)
        }

        assertBelowStatusBar("connections", compose.onNodeWithText("Herdroid").fetchSemanticsNode().boundsInRoot.top)
        fixture.routes.setRoutes(listOf(testRouteSummary()))
        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open terminal").performScrollTo().performClick()
        assertBelowStatusBar(
            "terminal",
            compose.onNodeWithContentDescription("Back to connections").fetchSemanticsNode().boundsInRoot.top,
        )
        compose.onNodeWithContentDescription("Open hierarchy switcher").performClick()
        assertBelowStatusBar(
            "switcher",
            compose.onNodeWithContentDescription("Hierarchy switcher").fetchSemanticsNode().boundsInRoot.top,
        )
    }

    @Test
    fun production_app_selects_creates_confirms_close_and_returns_with_back() {
        fixture.routes.setRoutes(listOf(testRouteSummary()))
        fixture.session.publishState(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open terminal").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.terminalAttachments == listOf("work:p1") }
        compose.onNodeWithText("office").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open hierarchy switcher").performClick()

        compose.onNodeWithText("Pane two").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.hierarchyRequests.size == 1 }
        fixture.session.publishState(ConnectionState.Connected(
            5,
            mapOf("work" to hierarchy().copy(focusedPaneId = "p2")),
        ))
        compose.waitUntil(5_000) { fixture.terminalAttachments.lastOrNull() == "work:p2" }
        compose.onNodeWithContentDescription("Open hierarchy switcher").performClick()
        compose.onNodeWithContentDescription("Create").performClick()
        compose.onNodeWithContentDescription("Create space").performClick()
        compose.waitUntil(5_000) { fixture.hierarchyRequests.size == 2 }
        compose.onNodeWithContentDescription("Pane two actions").performScrollTo().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, fixture.hierarchyRequests.size)
        compose.onNodeWithContentDescription("Pane two actions").performScrollTo().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { fixture.hierarchyRequests.size == 3 }

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Back to connections").assertIsDisplayed().performClick()
        compose.onNodeWithText("Herdroid").assertIsDisplayed()
        assertEquals(
            listOf(
                "focusPane:work:p2",
                "createWorkspace:work",
                "closePane:work:p2",
            ),
            fixture.hierarchyRequests,
        )
    }

    @Test
    fun route_editor_creates_a_hardware_key_and_returns_with_it_selected() {
        fixture.keys.pauseNextCollection()
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.waitUntil(5_000) { fixture.keys.activeCollectors.get() == 1 }
        compose.onNodeWithContentDescription("Connection name input").performTextInput("draft marker")
        compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Create key for Target").performClick()
        compose.onNodeWithText("SSH keys").assertIsDisplayed()
        assertEquals(1, fixture.keys.maximumCollectors.get())
        assertEquals(2, fixture.keys.collectorSubscriptions.get())
        compose.onNodeWithContentDescription("Add hardware key").performClick()
        compose.onNodeWithContentDescription("Generate key").performClick()
        compose.onNodeWithText("Key name").performTextInput("new key")
        compose.onNodeWithContentDescription("Generate").performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Add connection").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection name input").assertTextEquals("draft marker")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Target hardware key new key")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Target hardware key new key").performScrollTo().assertIsDisplayed()
        assertEquals(1, fixture.keys.maximumCollectors.get())
        assertEquals(3, fixture.keys.collectorSubscriptions.get())
    }

    @Test
    fun system_back_from_keys_returns_to_the_initiating_editor() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Create key for Target").performClick()
        compose.onNodeWithContentDescription("Back to connection").assertIsDisplayed()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Add connection").assertIsDisplayed()
    }

    @Test
    fun compact_selector_scrolls_many_keys_and_its_create_action() {
        fixture.useManyKeys(30)
        compose.runOnIdle { compose.activity.window.setLayout(COMPACT_WIDTH, COMPACT_HEIGHT) }
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Use Target hardware key").performScrollTo().performClick()

        compose.onNodeWithContentDescription("Select key 30 for Target").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Target hardware key key 30").assertIsDisplayed()
        compose.onNodeWithContentDescription("Change Target hardware key").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Create key for Target").performScrollTo().performClick()
        compose.onNodeWithText("SSH keys").assertIsDisplayed()
    }

    private class ActivityFixture {
        val routes = FakeRouteStore()
        val savedPasswordContents = mutableListOf<String>()
        val savedPasswordBuffers = mutableListOf<ByteArray>()
        val routeRepository = object : RouteRepository by routes {
            override suspend fun save(input: RouteWriteInput): Long {
                listOfNotNull(
                    input.target.authentication as? EndpointAuthenticationInput.Password,
                    input.jump?.authentication as? EndpointAuthenticationInput.Password,
                ).forEach { password ->
                    savedPasswordBuffers += password.ownedBytes()
                    val copy = password.copyForTransaction()
                    try {
                        savedPasswordContents += copy.decodeToString()
                    } finally {
                        copy.fill(0)
                    }
                }
                return this@ActivityFixture.routes.save(input)
            }
        }
        val session = FakeConnectionSession()
        val keys = FakeKeyVault(listOf(savedKey(7, "phone")))
        val hierarchyRequests get() = session.hierarchyCalls
        val terminalAttachments
            get() = session.attachRequests.map { "${it.sessionId}:${it.paneId}" }
        fun reset() {
            routes.setRoutes(emptyList())
            savedPasswordContents.clear()
            savedPasswordBuffers.clear()
            session.publishState(ConnectionState.Disconnected)
            session.publishDiagnostics(emptyList())
            keys.setKeys(listOf(savedKey(7, "phone")))
            session.hierarchyCalls.clear()
            session.attachRequests.clear()
        }

        fun useManyKeys(count: Int) {
            keys.setKeys((1..count).map { savedKey(it.toLong(), "key $it") })
        }

        private fun savedKey(id: Long, name: String) = testHardwareKeyMetadata(
            id = id,
            name = name,
            fingerprint = "SHA256:$id",
            authorizedKeyLine = "ecdsa-sha2-nistp256 PUBLIC herdroid:$name",
        )

        private fun EndpointAuthenticationInput.Password.ownedBytes(): ByteArray =
            javaClass.getDeclaredField("ownedBytes").run {
                isAccessible = true
                get(this@ownedBytes) as ByteArray
            }
    }

    private fun hierarchy() = testSessionState(
        secondaryPaneAgentStatus = AgentStatus.Idle,
    )
}

private const val COMPACT_WIDTH = 320
private const val COMPACT_HEIGHT = 480

private fun MainActivity.isImeVisible() = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
