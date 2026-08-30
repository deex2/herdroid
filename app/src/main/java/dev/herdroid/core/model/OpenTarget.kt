package dev.herdroid.core.model

data class OpenTargetIdentifiers(
    val routeId: Long,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val tabId: String? = null,
    val paneId: String? = null,
    val epoch: String? = null,
    val incarnation: Long? = null,
) {
    companion object {
        const val STALE_MESSAGE = "This notification is stale. Choose a live target instead."
    }
}

enum class OpenLevel { Routes, Sessions, Workspaces, Tabs, Panes, Pane }

data class OpenResolution(
    val level: OpenLevel,
    val session: String? = null,
    val workspaceId: String? = null,
    val tabId: String? = null,
    val paneId: String? = null,
    val message: String? = null,
)
