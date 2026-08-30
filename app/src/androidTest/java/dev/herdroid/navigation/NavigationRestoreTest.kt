package dev.herdroid.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dev.herdroid.MainActivity
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
import dev.herdroid.core.model.SavedRouteSummary
import dev.herdroid.core.model.SshEndpointSummary
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.core.model.Workspace
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.api.ConnectStage
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.session.api.TerminalLease
import dev.herdroid.session.impl.SessionModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.jvm.JvmSuppressWildcards
import org.junit.Assert.assertEquals
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
class NavigationRestoreTest {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(5, mapOf("work" to hierarchy())))
    private val routes = FakeRouteStore(listOf(SavedRouteSummary(5, "office", SshEndpointSummary("host", 22, "me"), null, false)))
    private val vault = FakeKeyVault()
    private val session = FakeConnectionSession()
    private val availability = MutableStateFlow<LocalDataAvailability>(LocalDataAvailability.Available)

    @BindValue @JvmField internal val routeRepository: RouteRepository = routes
    @BindValue @JvmField internal val connectionRouteRepository: ConnectionRouteRepository = routes
    @BindValue @JvmField internal val keyVault: KeyVault = vault
    @BindValue @JvmField internal val connectionSession: ConnectionSession = session
    @BindValue @JvmField internal val localDataAvailability:
        StateFlow<@JvmSuppressWildcards LocalDataAvailability> = availability

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun registerSession() {
        hilt.inject()
        session.enqueueTerminalLease(RestoreLease())
        publish()
    }

    @Test
    fun newOwnerRestoresStaleTerminalWithoutAnotherLease() {
        openTerminal()

        val original = compose.activity
        var attachesBeforeRestore = 0
        compose.activityRule.scenario.onActivity { activity ->
            state.value = ConnectionState.Connected(5, mapOf("work" to hierarchy("e2", 1)))
            publish()
            activity.viewModelStore.clear()
            attachesBeforeRestore = session.attachCalls.get()
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { compose.activity !== original }

        compose.onNodeWithText("Herdroid").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(OpenTargetIdentifiers.STALE_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(OpenTargetIdentifiers.STALE_MESSAGE).assertIsDisplayed()
        assertEquals(attachesBeforeRestore, session.attachCalls.get())
    }

    @Test
    fun liveTerminalSurvivesEveryReconnectStageAndRecreationBeforeReattaching() {
        openTerminal()

        compose.runOnIdle {
            session.enqueueTerminalLease(RestoreLease())
            state.value = ConnectionState.Reconnecting(5, 1)
            publish()
        }

        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
        assertEquals(1, session.attachCalls.get())

        compose.runOnIdle {
            state.value = ConnectionState.Connecting(5, ConnectStage.LoadingRoute)
            publish()
        }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
        assertEquals(1, session.attachCalls.get())

        val original = compose.activity
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { compose.activity !== original }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
        assertEquals(1, session.attachCalls.get())

        compose.runOnIdle {
            state.value = ConnectionState.Connecting(5, ConnectStage.DiscoveringHerdr)
            publish()
        }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
        assertEquals(1, session.attachCalls.get())

        compose.runOnIdle {
            state.value = ConnectionState.Connected(5, mapOf("work" to hierarchy("e2", 1)))
            publish()
        }
        compose.waitUntil(5_000) { session.attachCalls.get() == 2 }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
    }

    private fun openTerminal() {
        val target = NotificationOpenPayload(
            OpenTargetIdentifiers(5, "work", "w1", "t1", "p1", "e1", 0),
            AgentStatus.Working,
        )
        compose.runOnIdle {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", android.content.Intent::class.java)
                .apply { isAccessible = true }
                .invoke(compose.activity, target.toIntent(compose.activity))
        }
        compose.waitUntil(5_000) { session.attachCalls.get() == 1 }
        compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
    }

    private fun publish() {
        session.publishReady(true)
        session.publishState(state.value)
    }

    private class RestoreLease : TerminalLease {
        override val state = MutableStateFlow<TerminalState>(TerminalState.Attaching)
        override val frames = emptyFlow<TerminalFrame>()
        override fun sendText(text: String) = Unit
        override fun sendBytes(bytes: ByteArray) = Unit
        override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) = Unit
        override fun scroll(
            direction: TerminalScrollDirection,
            lines: Int,
            source: TerminalScrollSource,
            column: Int?,
            row: Int?,
            modifiers: Int,
        ) = Unit
        override fun close() = Unit
    }

    private companion object {
        private fun hierarchy(epoch: String = "e1", incarnation: Long = 0) = SessionState(
            epoch = epoch,
            incarnation = incarnation,
            workspaces = mapOf("w1" to Workspace("w1", 1, "Space", true, 1, 1, "t1", AgentStatus.Working)),
            tabs = mapOf("t1" to Tab("t1", "w1", 1, "Tab", true, 1, AgentStatus.Working)),
            panes = mapOf("p1" to Pane("p1", "term", "w1", "t1", true, agentStatus = AgentStatus.Working)),
            focusedWorkspaceId = "w1",
            focusedTabId = "t1",
            focusedPaneId = "p1",
        )
    }
}
