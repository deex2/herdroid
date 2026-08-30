package dev.herdroid.navigation

import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.HostTrustPrompt
import dev.herdroid.session.api.HostKeyResetPrompt
import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.Workspace
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.core.model.OpenLevel
import dev.herdroid.session.api.resolve
import dev.herdroid.session.api.livePane
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdroidDestinationTest {
    @Test
    fun terminalDestinationRoundTripsEveryIdentifier() {
        val expected = Terminal(7, "work", "w1", "t1", "p1", "e1", 3, OpenLevel.Panes.name)

        val decoded = Json.decodeFromString<Terminal>(Json.encodeToString(expected))

        assertEquals(expected, decoded)
        assertEquals(
            dev.herdroid.core.model.OpenTargetIdentifiers(7, "work", "w1", "t1", "p1", "e1", 3),
            decoded.toIdentifiers(),
        )
    }

    @Test
    fun secureWindowPolicyCoversEveryDestinationAndApproval() {
        val destinations = listOf(Connections, EditConnection(), Keys, Terminal(7))
        destinations.forEach { destination ->
            assertEquals(destination != Connections, secureWindowRequired(destination, ConnectionState.Disconnected))
        }
        val trust = ConnectionState.NeedsTrust(
            HostTrustPrompt(7, HostKeyCandidate(Hop.TARGET, "host", 22, "ssh-ed25519", "SHA256:x", "key")),
        )
        val reset = ConnectionState.NeedsHostKeyReset(
            HostKeyResetPrompt(7, trust.prompt.candidate, trust.prompt.candidate.copy(sha256 = "SHA256:y")),
        )
        val install = ConnectionState.NeedsBridgeApproval(
            BridgeApproval("route", RemoteOperatingSystem.LINUX, "x86_64", "target", "/root", "0.1.0", "0.8.0", "a".repeat(64)),
        )
        listOf(trust, reset, install).forEach { approval ->
            assertTrue(secureWindowRequired(Connections, approval))
        }
        assertFalse(secureWindowRequired(Connections, ConnectionState.Connected(7, emptyMap())))
    }

    @Test
    fun staleHierarchyFallsBackToTheDeepestLiveParentButAStaleRouteDoesNot() {
        val hierarchy = SessionState(
            epoch = "e1",
            incarnation = 3,
            workspaces = mapOf("w1" to Workspace("w1", 1, "Space", true, 1, 1, "t1", AgentStatus.Working)),
            tabs = mapOf("t1" to Tab("t1", "w1", 1, "Tab", true, 1, AgentStatus.Working)),
            panes = mapOf("p1" to Pane("p1", "term", "w1", "t1", true, agentStatus = AgentStatus.Working)),
            focusedWorkspaceId = "w1",
            focusedTabId = "t1",
            focusedPaneId = "p1",
        )
        val state = ConnectionState.Connected(7, mapOf("work" to hierarchy))
        val exact = OpenTargetIdentifiers(7, "work", "w1", "t1", "p1", "e1", 3)
        val partialTargets = listOf(
            exact.copy(sessionId = "stale"),
            exact.copy(workspaceId = "stale"),
            exact.copy(tabId = "stale"),
            exact.copy(paneId = "stale"),
            exact.copy(epoch = "stale"),
            exact.copy(incarnation = 4),
        )

        assertNull(exact.copy(routeId = 8).resolve(state).livePane(state))
        partialTargets.forEach { stale -> assertEquals(exact, stale.resolve(state).livePane(state)) }
        assertEquals(exact, exact.resolve(state).livePane(state))
    }
}
