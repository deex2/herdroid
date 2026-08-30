package dev.herdroid.core.testing

import dev.herdroid.core.data.EditableEndpoint
import dev.herdroid.core.data.EditableRoute
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SavedRouteSummary
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.SshEndpointSummary
import dev.herdroid.core.model.SshKeyOrigin
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.Workspace

fun testRouteSummary(
    id: Long = 5,
    name: String = "office",
    hostname: String = "host",
    port: Int = 22,
    username: String = "me",
    jump: SshEndpointSummary? = null,
    usesHardwareKey: Boolean = false,
) = SavedRouteSummary(id, name, SshEndpointSummary(hostname, port, username), jump, usesHardwareKey)

fun testEditableRoute(
    id: Long = 5,
    name: String = "office",
    target: EditableEndpoint = EditableEndpoint("host", 22, "me", null, null),
    jump: EditableEndpoint? = null,
) = EditableRoute(id, name, target, jump)

fun testHardwareKeyMetadata(
    id: Long = 7,
    name: String = "phone",
    fingerprint: String = "SHA256:7",
    origin: SshKeyOrigin = SshKeyOrigin.GENERATED,
    securityLevel: HardwareSecurityLevel = HardwareSecurityLevel.TEE,
    createdAtEpochMillis: Long = 1,
    routeUseCount: Int = 0,
    authorizedKeyLine: String = "ecdsa-sha2-nistp256 PUBLIC $name",
) = HardwareKeyMetadata(
    id,
    name,
    fingerprint,
    origin,
    securityLevel,
    createdAtEpochMillis,
    routeUseCount,
    authorizedKeyLine,
)

fun testSessionState(
    epoch: String = "e1",
    incarnation: Long = 0,
    workspaceId: String = "w1",
    workspaceLabel: String = "Space one",
    tabId: String = "t1",
    tabLabel: String = "Tab one",
    paneId: String = "p1",
    paneLabel: String = "Pane one",
    agentStatus: AgentStatus = AgentStatus.Working,
    paneAgentStatus: AgentStatus = agentStatus,
    secondaryPaneAgentStatus: AgentStatus? = null,
    focusedPaneId: String = paneId,
): SessionState {
    val paneCount = if (secondaryPaneAgentStatus == null) 1 else 2
    val panes = buildMap {
        put(
            paneId,
            Pane(
                paneId = paneId,
                terminalId = "term1",
                workspaceId = workspaceId,
                tabId = tabId,
                focused = focusedPaneId == paneId,
                label = paneLabel,
                agentStatus = paneAgentStatus,
            ),
        )
        secondaryPaneAgentStatus?.let {
            put(
                "p2",
                Pane(
                    paneId = "p2",
                    terminalId = "term2",
                    workspaceId = workspaceId,
                    tabId = tabId,
                    focused = focusedPaneId == "p2",
                    label = "Pane two",
                    agentStatus = it,
                ),
            )
        }
    }
    return SessionState(
        epoch = epoch,
        incarnation = incarnation,
        workspaces = mapOf(
            workspaceId to Workspace(workspaceId, 1, workspaceLabel, true, paneCount, 1, tabId, agentStatus),
        ),
        tabs = mapOf(tabId to Tab(tabId, workspaceId, 1, tabLabel, true, paneCount, agentStatus)),
        panes = panes,
        focusedWorkspaceId = workspaceId,
        focusedTabId = tabId,
        focusedPaneId = focusedPaneId,
    )
}
