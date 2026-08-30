package dev.herdroid.core.model

enum class AgentStatus { Idle, Working, Blocked, Done, Unknown }

data class WorkspaceWorktree(
    val repoKey: String,
    val repoName: String,
    val repoRoot: String,
    val checkoutPath: String,
    val isLinkedWorktree: Boolean,
)

data class Workspace(
    val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val paneCount: Int,
    val tabCount: Int,
    val activeTabId: String,
    val agentStatus: AgentStatus,
    val worktree: WorkspaceWorktree? = null,
)

data class Tab(
    val tabId: String,
    val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val paneCount: Int,
    val agentStatus: AgentStatus,
)

data class Pane(
    val paneId: String,
    val terminalId: String,
    val workspaceId: String,
    val tabId: String,
    val focused: Boolean,
    val cwd: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    val displayAgent: String? = null,
    val agentStatus: AgentStatus,
)

data class SessionState(
    val epoch: String,
    val incarnation: Long = 0,
    val baselineGeneration: Long = 0,
    val workspaces: Map<String, Workspace> = emptyMap(),
    val tabs: Map<String, Tab> = emptyMap(),
    val panes: Map<String, Pane> = emptyMap(),
    val focusedWorkspaceId: String? = null,
    val focusedTabId: String? = null,
    val focusedPaneId: String? = null,
    val uncoveredAgentPaneIds: Set<String> = emptySet(),
)

enum class SplitDirection { Right, Down }

enum class ZoomMode { Toggle, On, Off }

sealed interface ActionOutcome {
    data object Succeeded : ActionOutcome
    data class HostConfirmationRequired(val detail: String) : ActionOutcome
}
