package dev.herdroid.navigation

import dev.herdroid.session.api.ConnectionState
import dev.herdroid.core.model.OpenTargetIdentifiers
import kotlinx.serialization.Serializable

sealed interface HerdroidDestination

@Serializable
data object Connections : HerdroidDestination

@Serializable
data class EditConnection(val routeId: Long? = null, val duplicate: Boolean = false) : HerdroidDestination

@Serializable
data object Keys : HerdroidDestination

@Serializable
data class Terminal(
    val routeId: Long,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val tabId: String? = null,
    val paneId: String? = null,
    val epoch: String? = null,
    val incarnation: Long? = null,
    val openLevel: String? = null,
) : HerdroidDestination

fun Terminal.toIdentifiers() = OpenTargetIdentifiers(
    routeId,
    sessionId,
    workspaceId,
    tabId,
    paneId,
    epoch,
    incarnation,
)

fun secureWindowRequired(destination: HerdroidDestination, connectionState: ConnectionState) =
    destination != Connections || connectionState is ConnectionState.NeedsTrust ||
        connectionState is ConnectionState.NeedsHostKeyReset ||
        connectionState is ConnectionState.NeedsBridgeApproval
