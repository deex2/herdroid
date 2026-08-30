package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.model.decodeSessionSnapshot
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.Workspace
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrModelsTest {
    @Test
    fun `decodes stock snapshot hierarchy worktree and all agent statuses`() {
        val snapshot = decodeSessionSnapshot(
            Json.parseToJsonElement(
                """
                {
                  "version":"0.8.0","protocol":16,
                  "focused_workspace_id":"w1","focused_tab_id":"t1","focused_pane_id":"p1",
                  "workspaces":[
                    {"workspace_id":"w1","number":1,"label":"Main","focused":true,"pane_count":3,"tab_count":1,"active_tab_id":"t1","agent_status":"idle","unknown":"ignored",
                     "worktree":{"repo_key":"/repo/.git","repo_name":"repo","repo_root":"/repo","checkout_path":"/worktrees/feature","is_linked_worktree":true}},
                    {"workspace_id":"w2","number":2,"label":"Other","focused":false,"pane_count":0,"tab_count":0,"active_tab_id":"missing","agent_status":"working"}
                  ],
                  "tabs":[
                    {"tab_id":"t1","workspace_id":"w1","number":1,"label":"Code","focused":true,"pane_count":3,"agent_status":"working"}
                  ],
                  "panes":[
                    {"pane_id":"p1","terminal_id":"term1","workspace_id":"w1","tab_id":"t1","focused":true,"label":"Agent","display_agent":"Claude","agent_status":"blocked","revision":7},
                    {"pane_id":"p2","terminal_id":"term2","workspace_id":"w1","tab_id":"t1","focused":false,"agent_status":"done","revision":8},
                    {"pane_id":"p3","terminal_id":"term3","workspace_id":"w1","tab_id":"t1","focused":false,"agent_status":"unknown","revision":9}
                  ],
                  "layouts":[],"agents":[],"future":{"safe":true}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("w1", "w2"), snapshot.workspaces.keys.toList())
        assertEquals(AgentStatus.Idle, snapshot.workspaces.getValue("w1").agentStatus)
        assertEquals(AgentStatus.Working, snapshot.workspaces.getValue("w2").agentStatus)
        assertEquals(AgentStatus.Working, snapshot.tabs.getValue("t1").agentStatus)
        assertEquals(
            listOf(AgentStatus.Blocked, AgentStatus.Done, AgentStatus.Unknown),
            snapshot.panes.values.map(Pane::agentStatus),
        )
        assertEquals("Claude", snapshot.panes.getValue("p1").displayAgent)
        assertEquals("/worktrees/feature", snapshot.workspaces.getValue("w1").worktree?.checkoutPath)
        assertTrue(snapshot.workspaces.getValue("w1").worktree?.isLinkedWorktree == true)
        assertEquals("w1", snapshot.focusedWorkspaceId)
        assertEquals("t1", snapshot.focusedTabId)
        assertEquals("p1", snapshot.focusedPaneId)
    }

    @Test
    fun `empty and missing descendants decode safely into immutable maps`() {
        val snapshot = decodeSessionSnapshot(
            Json.parseToJsonElement(
                """{"version":"0.8.0","protocol":16,"workspaces":[{"workspace_id":"w1","number":1,"label":"Only","focused":true,"pane_count":0,"tab_count":1,"active_tab_id":"missing","agent_status":"idle"}],"layouts":[],"agents":[]}""",
            ),
        )

        assertEquals(setOf("w1"), snapshot.workspaces.keys)
        assertTrue(snapshot.tabs.isEmpty())
        assertTrue(snapshot.panes.isEmpty())
        assertNull(snapshot.focusedPaneId)
        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot.workspaces as MutableMap<String, Workspace>
        assertThrows(UnsupportedOperationException::class.java) { mutable.clear() }
    }
}
