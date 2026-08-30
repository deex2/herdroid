package dev.herdroid.core.herdr.wire

import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceWorktreeDto(
    @SerialName("repo_key") val repoKey: String,
    @SerialName("repo_name") val repoName: String,
    @SerialName("repo_root") val repoRoot: String,
    @SerialName("checkout_path") val checkoutPath: String,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean,
)

@Serializable
internal data class WorkspaceDto(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    @SerialName("pane_count") val paneCount: Int,
    @SerialName("tab_count") val tabCount: Int,
    @SerialName("active_tab_id") val activeTabId: String,
    @SerialName("agent_status") val agentStatus: String,
    val worktree: WorkspaceWorktreeDto? = null,
)

@Serializable
internal data class TabDto(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    @SerialName("pane_count") val paneCount: Int,
    @SerialName("agent_status") val agentStatus: String,
)

@Serializable
internal data class PaneDto(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean,
    val cwd: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: String,
)

@Serializable
internal data class SnapshotDto(
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<WorkspaceDto> = emptyList(),
    val tabs: List<TabDto> = emptyList(),
    val panes: List<PaneDto> = emptyList(),
)

internal fun SplitDirection.toWire() = when (this) {
    SplitDirection.Right -> "right"
    SplitDirection.Down -> "down"
}

internal fun ZoomMode.toWire() = when (this) {
    ZoomMode.Toggle -> "toggle"
    ZoomMode.On -> "on"
    ZoomMode.Off -> "off"
}
