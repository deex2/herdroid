package dev.herdroid.session.api

import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode

interface HierarchyCommands {
    suspend fun createWorkspace(
        sessionId: String,
        cwd: String? = null,
        label: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ActionOutcome

    suspend fun focusWorkspace(sessionId: String, workspaceId: String): ActionOutcome
    suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String): ActionOutcome
    suspend fun closeWorkspace(sessionId: String, workspaceId: String): ActionOutcome
    suspend fun createTab(
        sessionId: String,
        workspaceId: String? = null,
        cwd: String? = null,
        label: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ActionOutcome

    suspend fun focusTab(sessionId: String, tabId: String): ActionOutcome
    suspend fun renameTab(sessionId: String, tabId: String, label: String): ActionOutcome
    suspend fun closeTab(sessionId: String, tabId: String): ActionOutcome
    suspend fun focusPane(sessionId: String, paneId: String): ActionOutcome
    suspend fun splitPane(
        sessionId: String,
        paneId: String,
        direction: SplitDirection,
        ratio: Double? = null,
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ActionOutcome

    suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode): ActionOutcome
    suspend fun renamePane(sessionId: String, paneId: String, label: String): ActionOutcome
    suspend fun closePane(sessionId: String, paneId: String): ActionOutcome
}
