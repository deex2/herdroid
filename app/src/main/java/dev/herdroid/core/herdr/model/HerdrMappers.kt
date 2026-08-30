package dev.herdroid.core.herdr.model

import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.Workspace
import dev.herdroid.core.model.WorkspaceWorktree
import dev.herdroid.core.herdr.wire.PaneDto
import dev.herdroid.core.herdr.wire.SnapshotDto
import dev.herdroid.core.herdr.wire.TabDto
import dev.herdroid.core.herdr.wire.WorkspaceDto
import dev.herdroid.core.herdr.wire.WorkspaceWorktreeDto
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal data class SnapshotHierarchy(
    val workspaces: Map<String, Workspace>,
    val tabs: Map<String, Tab>,
    val panes: Map<String, Pane>,
    val focusedWorkspaceId: String?,
    val focusedTabId: String?,
    val focusedPaneId: String?,
)

private fun WorkspaceWorktreeDto.toModel() =
    WorkspaceWorktree(repoKey, repoName, repoRoot, checkoutPath, isLinkedWorktree)

private fun WorkspaceDto.toModel() = Workspace(
    workspaceId, number, label, focused, paneCount, tabCount, activeTabId,
    agentStatusFromWire(agentStatus), worktree?.toModel(),
)

private fun TabDto.toModel() =
    Tab(tabId, workspaceId, number, label, focused, paneCount, agentStatusFromWire(agentStatus))

private fun PaneDto.toModel() = Pane(
    paneId, terminalId, workspaceId, tabId, focused, cwd, label, agent, title, displayAgent,
    agentStatusFromWire(agentStatus),
)

private val snapshotJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

internal fun decodeSessionSnapshot(value: JsonElement): SnapshotHierarchy {
    val snapshot = snapshotJson.decodeFromJsonElement(SnapshotDto.serializer(), value)
    val workspaces = snapshot.workspaces.map { it.toModel() }
    val tabs = snapshot.tabs.map { it.toModel() }
    val panes = snapshot.panes.map { it.toModel() }
    return SnapshotHierarchy(
        immutableMap(workspaces.associateBy(Workspace::workspaceId)),
        immutableMap(tabs.associateBy(Tab::tabId)),
        immutableMap(panes.associateBy(Pane::paneId)),
        snapshot.focusedWorkspaceId,
        snapshot.focusedTabId,
        snapshot.focusedPaneId,
    )
}

internal fun agentStatusFromWire(value: String) = when (value) {
    "idle" -> AgentStatus.Idle
    "working" -> AgentStatus.Working
    "blocked" -> AgentStatus.Blocked
    "done" -> AgentStatus.Done
    else -> AgentStatus.Unknown
}

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    if (values.isEmpty()) emptyMap() else Collections.unmodifiableMap(LinkedHashMap(values))

internal fun <T> immutableSet(values: Collection<T>): Set<T> =
    if (values.isEmpty()) emptySet() else Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <T> immutableList(values: Collection<T>): List<T> =
    if (values.isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(values))
