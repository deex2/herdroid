package dev.herdroid.session.impl

import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.core.herdr.HerdrActions
import dev.herdroid.session.api.HierarchyCommands

internal class HerdrHierarchyCommands(
    private val current: () -> HerdrActions?,
) : HierarchyCommands {
    override suspend fun createWorkspace(
        sessionId: String,
        cwd: String?,
        label: String?,
        env: Map<String, String>,
    ) = actions().createWorkspace(sessionId, cwd, label, env)

    override suspend fun focusWorkspace(sessionId: String, workspaceId: String) =
        actions().focusWorkspace(sessionId, workspaceId)

    override suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String) =
        actions().renameWorkspace(sessionId, workspaceId, label)

    override suspend fun closeWorkspace(sessionId: String, workspaceId: String) =
        actions().closeWorkspace(sessionId, workspaceId)

    override suspend fun createTab(
        sessionId: String,
        workspaceId: String?,
        cwd: String?,
        label: String?,
        env: Map<String, String>,
    ) = actions().createTab(sessionId, workspaceId, cwd, label, env)

    override suspend fun focusTab(sessionId: String, tabId: String) = actions().focusTab(sessionId, tabId)
    override suspend fun renameTab(sessionId: String, tabId: String, label: String) =
        actions().renameTab(sessionId, tabId, label)

    override suspend fun closeTab(sessionId: String, tabId: String) = actions().closeTab(sessionId, tabId)
    override suspend fun focusPane(sessionId: String, paneId: String) = actions().focusPane(sessionId, paneId)

    override suspend fun splitPane(
        sessionId: String,
        paneId: String,
        direction: SplitDirection,
        ratio: Double?,
        cwd: String?,
        env: Map<String, String>,
    ) = actions().splitPane(sessionId, paneId, direction, ratio, cwd, env)

    override suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode) =
        actions().zoomPane(sessionId, paneId, mode)

    override suspend fun renamePane(sessionId: String, paneId: String, label: String) =
        actions().renamePane(sessionId, paneId, label)

    override suspend fun closePane(sessionId: String, paneId: String) = actions().closePane(sessionId, paneId)

    private fun actions() = checkNotNull(current()) { "The Herdr connection is not ready." }
}
