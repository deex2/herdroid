package dev.herdroid.session.api

import dev.herdroid.core.model.OpenLevel
import dev.herdroid.core.model.OpenResolution
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState

private const val STALE_MESSAGE = OpenTargetIdentifiers.STALE_MESSAGE

fun OpenTargetIdentifiers.resolve(state: ConnectionState): OpenResolution {
        val connected = state as? ConnectionState.Connected
            ?: return OpenResolution(OpenLevel.Routes, message = STALE_MESSAGE)
        if (connected.routeId != routeId) return OpenResolution(OpenLevel.Routes, message = STALE_MESSAGE)
        val sessionName = sessionId ?: connected.sessions.keys.firstOrNull()
            ?: return OpenResolution(OpenLevel.Routes, message = STALE_MESSAGE)
        val session = connected.sessions[sessionName]
            ?: return OpenResolution(OpenLevel.Sessions, message = STALE_MESSAGE)
        if (epoch != null && session.epoch != epoch || incarnation != null && session.incarnation != incarnation) {
            return OpenResolution(OpenLevel.Sessions, message = STALE_MESSAGE)
        }
        val workspace = workspaceId ?: session.focusedWorkspaceId ?: session.workspaces.keys.firstOrNull()
            ?: return OpenResolution(OpenLevel.Workspaces, sessionName, message = STALE_MESSAGE)
        if (session.workspaces[workspace]?.workspaceId != workspace) {
            return OpenResolution(OpenLevel.Workspaces, sessionName, message = STALE_MESSAGE)
        }
        val tab = tabId ?: session.focusedTabId ?: session.tabs.values.firstOrNull { it.workspaceId == workspace }?.tabId
            ?: return OpenResolution(OpenLevel.Tabs, sessionName, workspace, message = STALE_MESSAGE)
        if (session.tabs[tab]?.takeIf { it.tabId == tab && it.workspaceId == workspace } == null) {
            return OpenResolution(OpenLevel.Tabs, sessionName, workspace, message = STALE_MESSAGE)
        }
        val pane = paneId ?: session.focusedPaneId ?: session.panes.values.firstOrNull {
            it.workspaceId == workspace && it.tabId == tab
        }?.paneId ?: return OpenResolution(OpenLevel.Panes, sessionName, workspace, tab, message = STALE_MESSAGE)
        if (session.panes[pane]?.takeIf { it.paneId == pane && it.workspaceId == workspace && it.tabId == tab } == null) {
            return OpenResolution(OpenLevel.Panes, sessionName, workspace, tab, message = STALE_MESSAGE)
        }
        return OpenResolution(OpenLevel.Pane, sessionName, workspace, tab, pane)
}

fun OpenResolution.livePane(state: ConnectionState): OpenTargetIdentifiers? {
    val connected = state as? ConnectionState.Connected ?: return null
    val candidates: Sequence<Pair<String, SessionState>> = when (level) {
        OpenLevel.Routes -> emptySequence()
        OpenLevel.Sessions -> connected.sessions.asSequence().map { it.toPair() }
        else -> listOfNotNull(session?.let { name -> connected.sessions[name]?.let { name to it } }).asSequence()
    }
    return candidates.firstNotNullOfOrNull { (sessionName, sessionState) ->
        sessionState.livePane(workspaceId, tabId, paneId)?.let { pane ->
            OpenTargetIdentifiers(
                connected.routeId,
                sessionName,
                pane.workspaceId,
                pane.tabId,
                pane.paneId,
                sessionState.epoch,
                sessionState.incarnation,
            )
        }
    }
}

private fun SessionState.livePane(workspaceId: String?, tabId: String?, paneId: String?): Pane? {
    fun valid(pane: Pane) = (workspaceId == null || pane.workspaceId == workspaceId) &&
        (tabId == null || pane.tabId == tabId) && (paneId == null || pane.paneId == paneId)
    return focusedPaneId?.let(panes::get)?.takeIf(::valid) ?: panes.values.firstOrNull(::valid)
}
