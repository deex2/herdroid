package dev.herdroid.session.impl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.OpenLevel
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState
import dev.herdroid.navigation.AppNotificationTargetIntentFactory
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.resolve

fun shouldAlert(previous: AgentStatus?, next: AgentStatus, liveEpoch: Boolean) =
    liveEpoch && previous != null && previous != next &&
        (next == AgentStatus.Blocked || next == AgentStatus.Done)

internal class NotificationReconciler {
    private data class PaneKey(val session: String, val paneId: String)
    private data class SessionVersion(val epoch: String, val incarnation: Long, val baseline: Long)
    private data class SeenPane(
        val epoch: String,
        val baseline: Long,
        val workspaceId: String,
        val tabId: String,
        val status: AgentStatus,
    )

    private var routeId: Long? = null
    private val versions = mutableMapOf<String, SessionVersion>()
    private val retiredEpochs = mutableMapOf<String, MutableSet<String>>()
    private val seen = mutableMapOf<PaneKey, SeenPane>()

    @Synchronized
    fun reconcile(state: ConnectionState): List<AgentAlert> {
        val connected = state as? ConnectionState.Connected ?: return emptyList<AgentAlert>().also { clear() }
        if (routeId != connected.routeId) {
            clear()
            routeId = connected.routeId
        }
        val alerts = mutableListOf<AgentAlert>()
        versions.keys.retainAll(connected.sessions.keys)
        retiredEpochs.keys.retainAll(connected.sessions.keys)
        seen.keys.removeAll { it.session !in connected.sessions }
        connected.sessions.forEach { (name, session) -> reconcileSession(connected, name, session, alerts) }
        return alerts
    }

    private fun reconcileSession(
        connected: ConnectionState.Connected,
        name: String,
        session: SessionState,
        alerts: MutableList<AgentAlert>,
    ) {
        val version = SessionVersion(session.epoch, session.incarnation, session.baselineGeneration)
        val previousVersion = versions[name]
        if (previousVersion != null && previousVersion.epoch != session.epoch) {
            val retired = retiredEpochs.getOrPut(name, ::mutableSetOf)
            if (session.epoch in retired) return
            retired += previousVersion.epoch
        }
        if (previousVersion != version) {
            seen.keys.removeAll { it.session == name }
            versions[name] = version
        }
        val livePaneIds = session.panes.keys
        seen.keys.removeAll { it.session == name && it.paneId !in livePaneIds }
        session.panes.values.forEach { pane ->
            val key = PaneKey(name, pane.paneId)
            val next = pane.seen(session)
            val previous = seen.put(key, next)
            val sameIdentity = previous?.let {
                it.epoch == next.epoch && it.baseline == next.baseline &&
                    it.workspaceId == next.workspaceId && it.tabId == next.tabId
            } == true
            if (sameIdentity && shouldAlert(requireNotNull(previous).status, pane.agentStatus, liveEpoch = true)) {
                val target = AgentAlert(
                    OpenTargetIdentifiers(
                        connected.routeId,
                        name,
                        pane.workspaceId,
                        pane.tabId,
                        pane.paneId,
                        session.epoch,
                        session.incarnation,
                    ),
                    pane.agentStatus,
                )
                if (target.target.resolve(connected).level == OpenLevel.Pane) alerts += target
            }
        }
    }

    private fun Pane.seen(session: SessionState) = SeenPane(
        session.epoch,
        session.baselineGeneration,
        workspaceId,
        tabId,
        agentStatus,
    )

    private fun clear() {
        routeId = null
        versions.clear()
        retiredEpochs.clear()
        seen.clear()
    }
}

internal data class AgentAlert(val target: OpenTargetIdentifiers, val status: AgentStatus)

internal class NotificationFeed(private val reconciler: NotificationReconciler = NotificationReconciler()) {
    private var enabled = false

    @Synchronized
    fun promote() {
        enabled = true
    }

    @Synchronized
    fun disable() {
        enabled = false
        reconciler.reconcile(ConnectionState.Disconnected)
    }

    @Synchronized
    fun reconcile(state: ConnectionState.Connected): List<AgentAlert> =
        if (enabled) reconciler.reconcile(state) else emptyList()
}

internal class NotificationController(
    context: Context,
    private val targetIntents: AppNotificationTargetIntentFactory,
) : ServiceNotificationSink {
    private data class PaneKey(val session: String, val paneId: String)

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val feed = NotificationFeed()
    private val notificationIds = mutableMapOf<PaneKey, Int>()
    private var nextNotificationId = FIRST_AGENT_NOTIFICATION_ID

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CONNECTION_CHANNEL, "Connections", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(AGENT_CHANNEL, "Agent attention", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun connectionNotification(): Notification {
        val open = targetIntents.launchIntent(CONNECTION_NOTIFICATION_ID)
        return Notification.Builder(appContext, CONNECTION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Herdroid connected")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    fun agentNotification(target: OpenTargetIdentifiers, status: AgentStatus): Notification {
        val requestCode = notificationId(target)
        val open = targetIntents.targetIntent(target, status, requestCode)
        val title = if (status == AgentStatus.Blocked) "Agent is waiting" else "Agent finished"
        val identifiers = target
        return Notification.Builder(appContext, AGENT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText("${identifiers.sessionId} / ${identifiers.workspaceId} / ${identifiers.tabId} / ${identifiers.paneId}")
            .setGroup("${identifiers.routeId}:${identifiers.sessionId}:${identifiers.workspaceId}")
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .build()
    }

    @Synchronized
    override fun reconcile(state: ConnectionState.Connected) {
        feed.reconcile(state).forEach { alert ->
            try {
                manager.notify(notificationId(alert.target), agentNotification(alert.target, alert.status))
            } catch (_: RuntimeException) {
                // Permission or platform policy may change while the service is active.
            }
        }
    }

    @Synchronized
    override fun promote() = feed.promote()

    @Synchronized
    override fun disable() = feed.disable()

    @Synchronized
    private fun notificationId(target: OpenTargetIdentifiers) = notificationIds.getOrPut(
        PaneKey(requireNotNull(target.sessionId), requireNotNull(target.paneId)),
    ) {
        nextNotificationId++
    }

    companion object {
        const val CONNECTION_CHANNEL = "connection"
        const val AGENT_CHANNEL = "agent_attention"
        const val CONNECTION_NOTIFICATION_ID = 1
        private const val FIRST_AGENT_NOTIFICATION_ID = 1000
    }
}
