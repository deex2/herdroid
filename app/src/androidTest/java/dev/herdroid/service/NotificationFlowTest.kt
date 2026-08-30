package dev.herdroid.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
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
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.core.testing.testSessionState
import dev.herdroid.navigation.NotificationOpenPayload
import dev.herdroid.navigation.AppNotificationTargetIntentFactory
import dev.herdroid.navigation.consumeNotificationOpenIntent
import dev.herdroid.navigation.parseNotificationOpenIntent
import dev.herdroid.navigation.toIntent
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.impl.NotificationController
import dev.herdroid.session.impl.SessionModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmSuppressWildcards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.Before

@HiltAndroidTest
@UninstallModules(
    RepositoryModule::class,
    KeyVaultModule::class,
    LocalDataAvailabilityModule::class,
    SessionModule::class,
)
class NotificationFlowTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
    private val routes = FakeRouteStore()
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

    @Before fun registerFakeSession() {
        hilt.inject()
        publishSession()
    }

    private fun publishSession() {
        session.publishReady(true)
        session.publishState(state.value)
    }

    @Test
    fun native_channels_and_notifications_keep_the_local_one_open_action_contract() {
        val controller = NotificationController(context, AppNotificationTargetIntentFactory(context))
        controller.createChannels()

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(NotificationController.CONNECTION_CHANNEL).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(NotificationController.AGENT_CHANNEL).importance)

        val ongoing = controller.connectionNotification()
        assertEquals(NotificationController.CONNECTION_CHANNEL, ongoing.channelId)
        assertEquals(Notification.FLAG_ONGOING_EVENT, ongoing.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotNull(ongoing.contentIntent)

        val target = NotificationOpenPayload(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "p1", "e1", 0),
            AgentStatus.Blocked,
        )
        val alert = controller.agentNotification(target.identifiers, target.status)
        assertEquals(NotificationController.AGENT_CHANNEL, alert.channelId)
        assertEquals("7:work:w1", alert.group)
        val action = alert.actions.single()
        assertEquals("Open", action.title.toString())
        assertNotNull(action.actionIntent)
        assertEquals(action.actionIntent, alert.contentIntent)
        val intent = target.toIntent(context)
        assertEquals(target, parseNotificationOpenIntent(intent))
        assertNull(parseNotificationOpenIntent(target.toIntent(context).putExtra("status", "blocked")))
        val accepted = target.toIntent(context)
        assertEquals(target, consumeNotificationOpenIntent(accepted))
        assertNull(consumeNotificationOpenIntent(accepted))
        val rejected = target.toIntent(context).putExtra("status", "blocked")
        assertNull(consumeNotificationOpenIntent(rejected))
        assertNull(rejected.action)
        assertNull(rejected.extras)
        assertNull(
            parseNotificationOpenIntent(
                target.toIntent(context).setComponent(ComponentName(context, "dev.herdroid.LauncherActivity")),
            ),
        )
    }

    @Test
    fun main_activity_focuses_an_exact_target_and_explains_a_stale_pane() {
        val exact = NotificationOpenPayload(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "p2", "e1", 0),
            AgentStatus.Done,
        )
        compose.runOnIdle { deliver(exact) }
        compose.waitUntil(5_000) { session.hierarchyCalls.size == 1 }
        assertEquals(listOf("focusPane:work:p2"), session.hierarchyCalls)

        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedPane = "p2")))
        publishSession()
        compose.onNodeWithText("Pane two").assertDoesNotExist()

        val stale = exact.copy(identifiers = exact.identifiers.copy(paneId = "recycled"))
        compose.runOnIdle { deliver(stale) }
        compose.onNodeWithText(OpenTargetIdentifiers.STALE_MESSAGE).assertIsDisplayed()
        assertEquals(1, session.hierarchyCalls.size)
    }

    @Test
    fun stale_notification_opens_its_live_parent_switcher_with_an_explanation() {
        val stale = NotificationOpenPayload(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "gone", "e1", 0),
            AgentStatus.Done,
        )

        compose.runOnIdle { deliver(stale) }

        compose.onNodeWithContentDescription("Hierarchy switcher").assertIsDisplayed()
        compose.onNodeWithText(OpenTargetIdentifiers.STALE_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Pane one", substring = true).assertIsDisplayed()
        assertEquals(emptyList<String>(), session.hierarchyCalls)
    }

    @Test
    fun notification_waits_for_same_route_reconnect_before_resolving() {
        val target = NotificationOpenPayload(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "p2", "e1", 0),
            AgentStatus.Done,
        )
        state.value = ConnectionState.Reconnecting(7, 1)
        publishSession()

        compose.runOnIdle { deliver(target) }
        compose.onNodeWithText(OpenTargetIdentifiers.STALE_MESSAGE).assertDoesNotExist()
        assertEquals(emptyList<String>(), session.hierarchyCalls)

        state.value = ConnectionState.Connecting(7, dev.herdroid.session.api.ConnectStage.LoadingRoute)
        publishSession()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), session.hierarchyCalls)

        state.value = ConnectionState.Connected(7, mapOf("work" to session()))
        publishSession()
        compose.waitUntil(5_000) { session.hierarchyCalls.size == 1 }
        assertEquals(listOf("focusPane:work:p2"), session.hierarchyCalls)
    }

    @Test
    fun consumed_notification_does_not_replay_over_a_later_editor_after_recreation() {
        val target = NotificationOpenPayload(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "p2", "e1", 0),
            AgentStatus.Done,
        )
        compose.runOnIdle {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", android.content.Intent::class.java)
                .apply { isAccessible = true }
                .invoke(compose.activity, target.toIntent(compose.activity))
        }
        compose.waitUntil(5_000) { session.hierarchyCalls.size == 1 }
        compose.onNodeWithContentDescription("Back to connections", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithContentDescription("Connection name input").performTextInput("later draft")

        val original = compose.activity
        compose.runOnIdle { original.recreate() }
        compose.waitUntil(5_000) { compose.activity !== original }

        compose.onNodeWithText("Add connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection name input").assertTextEquals("later draft")
        assertEquals(1, session.hierarchyCalls.size)
    }

    @Test
    fun notification_waiting_for_session_survives_recreation_and_is_handled_once() {
        val target = NotificationOpenPayload(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "p2", "e1", 0),
            AgentStatus.Done,
        )
        compose.runOnIdle {
            session.publishReady(false)
            deliver(target)
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), session.hierarchyCalls)

        val waitingActivity = compose.activity
        compose.runOnIdle { waitingActivity.recreate() }
        compose.waitUntil(5_000) { compose.activity !== waitingActivity }
        assertEquals(emptyList<String>(), session.hierarchyCalls)

        compose.runOnIdle { deliver(Intent(compose.activity, MainActivity::class.java)) }
        assertEquals(emptyList<String>(), session.hierarchyCalls)

        compose.runOnIdle { session.publishReady(true) }
        compose.waitUntil(5_000) { session.hierarchyCalls.size == 1 }
        assertEquals(listOf("focusPane:work:p2"), session.hierarchyCalls)
        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedPane = "p2")))
        publishSession()

        val handledActivity = compose.activity
        compose.runOnIdle { handledActivity.recreate() }
        compose.waitUntil(5_000) { compose.activity !== handledActivity }
        compose.waitForIdle()
        assertEquals(listOf("focusPane:work:p2"), session.hierarchyCalls)
    }

    private fun deliver(target: NotificationOpenPayload) = deliver(target.toIntent(compose.activity))

    private fun deliver(intent: Intent) {
        val launchIntent = compose.activity.intent
        try {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", android.content.Intent::class.java)
                .apply { isAccessible = true }
                .invoke(compose.activity, intent)
        } finally {
            compose.activity.intent = launchIntent
        }
    }

    private fun session(focusedPane: String = "p1") = testSessionState(
        agentStatus = AgentStatus.Done,
        paneAgentStatus = AgentStatus.Working,
        secondaryPaneAgentStatus = AgentStatus.Done,
        focusedPaneId = focusedPane,
    )
}
