package dev.herdroid.session.impl

import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.OpenLevel
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.Workspace
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.resolve
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationControllerTest {
    @Test
    fun `only live changes of known panes into blocked or done alert`() {
        val tracker = NotificationReconciler()

        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Idle))))
        assertEquals(AgentStatus.Blocked, tracker.reconcile(connected(session(AgentStatus.Blocked))).single().status)
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Blocked))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Unknown))))
        assertEquals(AgentStatus.Done, tracker.reconcile(connected(session(AgentStatus.Done))).single().status)
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Idle))))
        assertEquals(AgentStatus.Done, tracker.reconcile(connected(session(AgentStatus.Done))).single().status)
    }

    @Test
    fun `initial new epoch baseline new pane disconnect and recycled id only seed state`() {
        val tracker = NotificationReconciler()

        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Blocked))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Done, paneId = "p2"))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Done, epoch = "e2"))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Blocked, epoch = "e1"))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Done, epoch = "e1"))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(ConnectionState.Disconnected))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Done))))

        tracker.reconcile(connected(session(AgentStatus.Working)))
        assertEquals(
            emptyList<AgentAlert>(),
            tracker.reconcile(connected(session(AgentStatus.Blocked, workspaceId = "w2", tabId = "t2"))),
        )
    }

    @Test
    fun `same epoch reconciliation alerts while baseline replacement and acknowledgement do not`() {
        val tracker = NotificationReconciler()
        tracker.reconcile(connected(session(AgentStatus.Working)))

        assertEquals(AgentStatus.Done, tracker.reconcile(connected(session(AgentStatus.Done))).single().status)
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Idle))))
        assertEquals(emptyList<AgentAlert>(), tracker.reconcile(connected(session(AgentStatus.Blocked, baseline = 1))))
        assertEquals(AgentStatus.Done, tracker.reconcile(connected(session(AgentStatus.Done, baseline = 1))).single().status)
    }

    @Test
    fun `concurrent duplicate and desktop acknowledgement produce one alert`() {
        val tracker = NotificationReconciler()
        tracker.reconcile(connected(session(AgentStatus.Working)))
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<AgentAlert>())
        val workers = List(16) {
            thread {
                start.await()
                results += tracker.reconcile(connected(session(AgentStatus.Blocked)))
            }
        }
        start.countDown()
        workers.forEach(Thread::join)
        assertEquals(1, results.size)

        tracker.reconcile(connected(session(AgentStatus.Working)))
        val desktop = thread { results += tracker.reconcile(connected(session(AgentStatus.Idle))) }
        val done = thread { results += tracker.reconcile(connected(session(AgentStatus.Done))) }
        desktop.join()
        done.join()
        assertEquals(2, results.size)
    }

    @Test
    fun `ordered feed seeds the new epoch then observes its first transition`() {
        val feed = NotificationFeed()
        feed.promote()
        feed.reconcile(connected(session(AgentStatus.Working, epoch = "e1")))
        feed.reconcile(connected(session(AgentStatus.Working, epoch = "e2")))

        assertEquals(AgentStatus.Done, feed.reconcile(connected(session(AgentStatus.Done, epoch = "e2"))).single().status)
        feed.disable()
        assertEquals(emptyList<AgentAlert>(), feed.reconcile(connected(session(AgentStatus.Blocked, epoch = "e2"))))
    }

    @Test
    fun `service owner wires accepted foreground into ordered coordinator transitions`() = runBlocking {
        val sink = RecordingNotificationSink()
        var activityBound = true
        val owner = ServiceNotificationOwner(sink) { activityBound = it }
        lateinit var lease: NotificationLease
        owner.replace(this) { opened ->
            lease = opened
            owner.foregroundResolved(opened, accepted = true, current = null)
        }.join()
        owner.connected(lease, connected(session(AgentStatus.Working)))
        owner.connected(lease, connected(session(AgentStatus.Done)))
        owner.connected(lease, connected(session(AgentStatus.Idle)))

        assertFalse(activityBound)
        assertEquals(listOf(AgentStatus.Done), sink.alerts.map(AgentAlert::status))
    }

    @Test
    fun `service owner keeps rejected foreground activity bound and silent`() = runBlocking {
        val sink = RecordingNotificationSink()
        var activityBound = false
        val owner = ServiceNotificationOwner(sink) { activityBound = it }
        lateinit var lease: NotificationLease
        owner.replace(this) { opened ->
            lease = opened
            owner.foregroundResolved(opened, accepted = false, current = connected(session(AgentStatus.Working)))
        }.join()
        owner.connected(lease, connected(session(AgentStatus.Working)))
        owner.connected(lease, connected(session(AgentStatus.Done)))

        assertTrue(activityBound)
        assertEquals(emptyList<AgentAlert>(), sink.alerts)
    }

    @Test
    fun `replacement disconnect and terminal requests close gate before blocked command processing`() = runBlocking {
        suspend fun verify(request: (ServiceNotificationOwner, CoroutineScope, suspend () -> Unit) -> Job) {
            val sink = RecordingNotificationSink()
            val owner = ServiceNotificationOwner(sink) {}
            lateinit var oldLease: NotificationLease
            owner.replace(this) { lease ->
                oldLease = lease
                owner.foregroundResolved(lease, true, current = null)
            }.join()
            owner.connected(oldLease, connected(session(AgentStatus.Working)))
            val commandGate = Mutex(locked = true)

            val command = request(owner, this, { commandGate.withLock {} })
            owner.foregroundResolved(oldLease, true, connected(session(AgentStatus.Working)))
            owner.connected(oldLease, connected(session(AgentStatus.Done)))

            assertEquals(emptyList<AgentAlert>(), sink.alerts)
            commandGate.unlock()
            command.join()
        }

        verify { owner, scope, command -> owner.replace(scope) { command() } }
        verify { owner, scope, command -> owner.disconnect(scope, command = command) }
        verify { owner, scope, command -> owner.terminal(scope, command) }
    }

    @Test
    fun `disconnect cancels synchronously before queued cleanup`() = runBlocking {
        val owner = ServiceNotificationOwner(RecordingNotificationSink()) {}
        val cleanupGate = Mutex(locked = true)
        var cancelled = false
        var cleanupStarted = false

        val job = owner.disconnect(this, cancelNow = { cancelled = true }) {
            cleanupStarted = true
            cleanupGate.withLock {}
        }

        assertTrue(cancelled)
        while (!cleanupStarted) kotlinx.coroutines.yield()
        assertTrue(job.isActive)
        cleanupGate.unlock()
        job.join()
    }

    @Test
    fun `service destruction closes notification gate immediately`() = runBlocking {
        val sink = RecordingNotificationSink()
        val owner = ServiceNotificationOwner(sink) {}
        lateinit var lease: NotificationLease
        owner.replace(this) { opened ->
            lease = opened
            owner.foregroundResolved(opened, true, connected(session(AgentStatus.Working)))
        }.join()

        owner.stopped()
        owner.connected(lease, connected(session(AgentStatus.Done)))

        assertEquals(emptyList<AgentAlert>(), sink.alerts)
    }

    @Test
    fun `open target requires the live route epoch and every hierarchy parent`() {
        val target = target()
        val live = connected(session(AgentStatus.Blocked))

        assertEquals(OpenLevel.Pane, target.resolve(live).level)
        assertEquals(OpenLevel.Routes, target.resolve(ConnectionState.Disconnected).level)
        assertEquals(OpenLevel.Routes, target.copy(routeId = 8).resolve(live).level)
        assertEquals(OpenLevel.Sessions, target.copy(epoch = "old").resolve(live).level)
        assertEquals(OpenLevel.Sessions, target.copy(incarnation = 2).resolve(live).level)
        assertEquals(OpenLevel.Sessions, target.copy(sessionId = "gone").resolve(live).level)
        assertEquals(OpenLevel.Workspaces, target.copy(workspaceId = "gone").resolve(live).level)
        assertEquals(OpenLevel.Tabs, target.copy(tabId = "gone").resolve(live).level)
        assertEquals(OpenLevel.Panes, target.copy(paneId = "gone").resolve(live).level)
        assertEquals(
            OpenLevel.Tabs,
            target.resolve(connected(session(AgentStatus.Blocked, tabWorkspaceId = "other"))).level,
        )
        assertEquals(
            OpenLevel.Panes,
            target.resolve(connected(session(AgentStatus.Blocked, paneWorkspaceId = "other"))).level,
        )
        assertEquals(
            OpenLevel.Panes,
            target.resolve(connected(session(AgentStatus.Blocked, paneTabId = "other"))).level,
        )
        val validSession = session(AgentStatus.Blocked)
        assertEquals(
            OpenLevel.Workspaces,
            target.resolve(connected(validSession.copy(
                workspaces = mapOf("w1" to validSession.workspaces.getValue("w1").copy(workspaceId = "other")),
            ))).level,
        )
        assertEquals(
            OpenLevel.Tabs,
            target.resolve(connected(validSession.copy(
                tabs = mapOf("t1" to validSession.tabs.getValue("t1").copy(tabId = "other")),
            ))).level,
        )
        assertEquals(
            OpenLevel.Panes,
            target.resolve(connected(validSession.copy(
                panes = mapOf("p1" to validSession.panes.getValue("p1").copy(paneId = "other")),
            ))).level,
        )
    }

    private fun connected(session: SessionState) = ConnectionState.Connected(7, mapOf("work" to session))

    private fun session(
        status: AgentStatus,
        epoch: String = "e1",
        paneId: String = "p1",
        workspaceId: String = "w1",
        tabId: String = "t1",
        baseline: Long = 0,
        tabWorkspaceId: String = workspaceId,
        paneWorkspaceId: String = workspaceId,
        paneTabId: String = tabId,
        incarnation: Long = 1,
    ) = SessionState(
        epoch = epoch,
        incarnation = incarnation,
        baselineGeneration = baseline,
        workspaces = mapOf(workspaceId to Workspace(workspaceId, 1, "Space", true, 1, 1, tabId, status)),
        tabs = mapOf(tabId to Tab(tabId, tabWorkspaceId, 1, "Tab", true, 1, status)),
        panes = mapOf(paneId to Pane(paneId, "term", paneWorkspaceId, paneTabId, true, agentStatus = status)),
        focusedWorkspaceId = workspaceId,
        focusedTabId = tabId,
        focusedPaneId = paneId,
    )

    private fun target() = OpenTargetIdentifiers(7, "work", "w1", "t1", "p1", "e1", incarnation = 1)

    private class RecordingNotificationSink : ServiceNotificationSink {
        private val feed = NotificationFeed()
        val alerts = mutableListOf<AgentAlert>()

        override fun promote() = feed.promote()
        override fun disable() = feed.disable()
        override fun reconcile(state: ConnectionState.Connected) {
            alerts += feed.reconcile(state)
        }
    }
}
