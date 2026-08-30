package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.BridgeSessionDescriptor
import dev.herdroid.core.herdr.wire.ServerMessage
import dev.herdroid.core.model.AgentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    @Test
    fun `snapshots replace one hierarchy atomically and non structural messages cannot edit it`() = runBlocking {
        val coordinator = coordinator(this)
        coordinator.accept(hello("e1"))
        coordinator.accept(sessions("one", "two"))
        coordinator.accept(snapshot("one", "e1", hierarchy("w1", "t1", "p1", "idle")))
        coordinator.accept(snapshot("two", "e1", hierarchy("w2", "t2", "p2", "working")))

        val before = coordinator.state.value.sessions.getValue("one")
        coordinator.accept(ServerMessage.Response("r", "one", hierarchy("evil", "evil", "evil", "done")))
        coordinator.accept(ServerMessage.AgentStatus("one", "e1", "p1", "blocked"))

        val route = coordinator.state.value
        val one = route.sessions.getValue("one")
        assertEquals(setOf("w1"), one.workspaces.keys)
        assertEquals(setOf("t1"), one.tabs.keys)
        assertEquals(setOf("p1"), one.panes.keys)
        assertEquals(AgentStatus.Blocked, one.panes.getValue("p1").agentStatus)
        assertEquals(before.focusedPaneId, one.focusedPaneId)
        assertEquals(setOf("w2"), route.sessions.getValue("two").workspaces.keys)
    }

    @Test
    fun `baseline and degraded replace only their current session state`() = runBlocking {
        val coordinator = coordinator(this)
        coordinator.accept(hello("e1"))
        coordinator.accept(sessions("one", "two"))
        coordinator.accept(snapshot("one", "e1", hierarchy("old", "old-tab", "old-pane", "done")))
        coordinator.accept(snapshot("two", "e1", hierarchy("keep", "keep-tab", "keep-pane", "idle")))
        coordinator.accept(ServerMessage.Degraded("one", "e1", "coverage", "text", listOf("old-pane")))
        coordinator.accept(ServerMessage.Degraded("one", "e1", "coverage", "text", listOf("new-pane")))

        assertEquals(setOf("new-pane"), coordinator.state.value.sessions.getValue("one").uncoveredAgentPaneIds)

        coordinator.accept(snapshot("one", "e1", hierarchy("new", "new-tab", "new-pane", "blocked"), baseline = true))

        val route = coordinator.state.value
        assertEquals(1, route.sessions.getValue("one").baselineGeneration)
        assertEquals(setOf("new"), route.sessions.getValue("one").workspaces.keys)
        assertTrue(route.sessions.getValue("one").uncoveredAgentPaneIds.isEmpty())
        assertEquals(setOf("keep"), route.sessions.getValue("two").workspaces.keys)
    }

    @Test
    fun `new epoch clears all and stale unknown events are ignored with bounded diagnostics`() = runBlocking {
        val coordinator = coordinator(this)
        coordinator.accept(hello("e1"))
        coordinator.accept(sessions("one"))
        coordinator.accept(snapshot("one", "e1", hierarchy("w1", "t1", "p1", "idle")))
        coordinator.accept(hello("e2"))

        assertEquals("e2", coordinator.state.value.epoch)
        assertTrue(coordinator.state.value.sessions.isEmpty())

        coordinator.accept(sessions("one"))
        coordinator.accept(snapshot("one", "e1", hierarchy("stale", "stale", "stale", "done")))
        coordinator.accept(ServerMessage.AgentStatus("missing", "e2", "p1", "done"))
        repeat(40) { coordinator.accept(ServerMessage.AgentStatus("one", "old-$it", "missing", "done")) }

        val route = coordinator.state.value
        assertTrue(route.sessions.getValue("one").panes.isEmpty())
        assertEquals(SessionCoordinator.MAX_DIAGNOSTICS, route.diagnostics.size)
        assertFalse(route.diagnostics.any(String::isBlank))
    }

    @Test
    fun `session discovery preserves retained state and removes vanished sessions`() = runBlocking {
        val coordinator = coordinator(this)
        coordinator.accept(hello("e1"))
        coordinator.accept(sessions("one", "two"))
        coordinator.accept(snapshot("one", "e1", hierarchy("w1", "t1", "p1", "idle")))
        coordinator.accept(sessions("one", "three"))

        assertEquals(setOf("one", "three"), coordinator.state.value.sessions.keys)
        assertEquals(setOf("p1"), coordinator.state.value.sessions.getValue("one").panes.keys)
        assertTrue(coordinator.state.value.sessions.getValue("three").panes.isEmpty())
    }

    @Test
    fun `stopped sessions disappear and a recreated name gets a new incarnation`() = runBlocking {
        val coordinator = coordinator(this)
        coordinator.accept(hello("e1"))
        coordinator.accept(sessions("one"))
        val first = coordinator.state.value.sessions.getValue("one").incarnation

        coordinator.accept(ServerMessage.Sessions(listOf(BridgeSessionDescriptor("one", false, "/tmp/one.sock"))))
        assertTrue(coordinator.state.value.sessions.isEmpty())

        coordinator.accept(sessions("one"))
        val second = coordinator.state.value.sessions.getValue("one").incarnation
        assertTrue(second > first)
    }

    @Test
    fun `incarnations remain monotonic across bridge reconnections`() = runBlocking {
        val firstConnection = coordinator(this)
        firstConnection.accept(hello("same-epoch"))
        firstConnection.accept(sessions("one"))
        val first = firstConnection.state.value.sessions.getValue("one").incarnation

        val secondConnection = coordinator(this)
        secondConnection.accept(hello("same-epoch"))
        secondConnection.accept(sessions("one"))

        assertTrue(secondConnection.state.value.sessions.getValue("one").incarnation > first)
    }

    @Test
    fun `construction rejects an inactive lifecycle scope`() {
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()

        val failure = assertThrows(IllegalStateException::class.java) {
            SessionCoordinator(emptyFlow(), scope)
        }

        assertEquals("Session coordinator scope is not active", failure.message)
    }

    private fun coordinator(scope: CoroutineScope) = SessionCoordinator(emptyFlow(), scope)

    private fun hello(epoch: String) = ServerMessage.Hello(1, epoch, "0.1.0", "linux", "x86_64", "0.8.0")

    private fun sessions(vararg names: String) = ServerMessage.Sessions(
        names.map { BridgeSessionDescriptor(it, true, "/tmp/$it.sock") },
    )

    private fun snapshot(session: String, epoch: String, hierarchy: kotlinx.serialization.json.JsonElement, baseline: Boolean = false) =
        ServerMessage.Snapshot(session, epoch, baseline, hierarchy)

    private fun hierarchy(workspace: String, tab: String, pane: String, status: String) = Json.parseToJsonElement(
        """
        {"version":"0.8.0","protocol":16,
         "focused_workspace_id":"$workspace","focused_tab_id":"$tab","focused_pane_id":"$pane",
         "workspaces":[{"workspace_id":"$workspace","number":1,"label":"Space","focused":true,"pane_count":1,"tab_count":1,"active_tab_id":"$tab","agent_status":"$status"}],
         "tabs":[{"tab_id":"$tab","workspace_id":"$workspace","number":1,"label":"Tab","focused":true,"pane_count":1,"agent_status":"$status"}],
         "panes":[{"pane_id":"$pane","terminal_id":"term","workspace_id":"$workspace","tab_id":"$tab","focused":true,"agent_status":"$status","revision":1}],
         "layouts":[],"agents":[]}
        """.trimIndent(),
    )
}
