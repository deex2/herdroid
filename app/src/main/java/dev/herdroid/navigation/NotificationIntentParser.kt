package dev.herdroid.navigation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.herdroid.MainActivity
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.OpenTargetIdentifiers

internal data class NotificationOpenPayload(
    val identifiers: OpenTargetIdentifiers,
    val status: AgentStatus,
)

internal fun NotificationOpenPayload.toIntent(context: Context) = Intent(context, MainActivity::class.java)
    .setAction(ACTION_OPEN)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    .putExtras(toBundle())

internal fun parseNotificationOpenIntent(intent: Intent?): NotificationOpenPayload? {
    if (intent?.action != ACTION_OPEN || intent.component?.className != MainActivity::class.java.name) return null
    return parseNotificationOpenPayload(intent.extras)
}

internal fun restoreNotificationOpenPayload(state: Bundle?): NotificationOpenPayload? =
    parseNotificationOpenPayload(state?.getBundle(SAVED_OPEN_TARGET))

internal fun saveNotificationOpenPayload(state: Bundle, payload: NotificationOpenPayload?) {
    payload?.let { state.putBundle(SAVED_OPEN_TARGET, it.toBundle()) }
}

private fun parseNotificationOpenPayload(extras: Bundle?): NotificationOpenPayload? {
    val routeId = extras?.getString(ROUTE_ID)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val session = extras.getString(SESSION_ID)?.takeIf(SESSION_PATTERN::matches) ?: return null
    val workspace = extras.getString(WORKSPACE_ID)?.takeIf(ID_PATTERN::matches) ?: return null
    val tab = extras.getString(TAB_ID)?.takeIf(ID_PATTERN::matches) ?: return null
    val pane = extras.getString(PANE_ID)?.takeIf(ID_PATTERN::matches) ?: return null
    val epoch = extras.getString(EPOCH)?.takeIf(ID_PATTERN::matches) ?: return null
    val incarnation = extras.getString(INCARNATION)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val status = extras.getString(STATUS)?.let { name -> AgentStatus.entries.singleOrNull { it.name == name } }
        ?.takeIf { it != AgentStatus.Unknown } ?: return null
    return NotificationOpenPayload(
        OpenTargetIdentifiers(routeId, session, workspace, tab, pane, epoch, incarnation),
        status,
    )
}

private fun NotificationOpenPayload.toBundle() = Bundle().apply {
    putString(ROUTE_ID, identifiers.routeId.toString())
    putString(SESSION_ID, identifiers.sessionId)
    putString(WORKSPACE_ID, identifiers.workspaceId)
    putString(TAB_ID, identifiers.tabId)
    putString(PANE_ID, identifiers.paneId)
    putString(EPOCH, identifiers.epoch)
    putString(INCARNATION, identifiers.incarnation?.toString())
    putString(STATUS, status.name)
}

internal fun consumeNotificationOpenIntent(intent: Intent?): NotificationOpenPayload? {
    if (intent?.action != ACTION_OPEN) return null
    val payload = parseNotificationOpenIntent(intent)
    intent.action = null
    intent.replaceExtras(null as Bundle?)
    return payload
}

private const val ACTION_OPEN = "dev.herdroid.action.OPEN_AGENT"
private const val ROUTE_ID = "route_id"
private const val SESSION_ID = "session_id"
private const val WORKSPACE_ID = "workspace_id"
private const val TAB_ID = "tab_id"
private const val PANE_ID = "pane_id"
private const val EPOCH = "epoch"
private const val INCARNATION = "incarnation"
private const val STATUS = "status"
private const val SAVED_OPEN_TARGET = "notification_open_target"
private val SESSION_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
