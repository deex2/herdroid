package dev.herdroid.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.herdroid.core.model.OpenLevel
import dev.herdroid.core.model.OpenResolution
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.session.api.livePane
import dev.herdroid.session.api.resolve
import dev.herdroid.feature.connections.CREATED_KEY_ID
import dev.herdroid.feature.connections.ConnectionsViewModel
import dev.herdroid.feature.connections.navigation.ConnectionEditorRoute
import dev.herdroid.feature.connections.navigation.ConnectionsRoute
import dev.herdroid.feature.keys.navigation.KeysRoute
import dev.herdroid.feature.terminal.navigation.TerminalRoute
import dev.herdroid.session.api.ConnectionSession

@Composable
internal fun HerdroidNavHost(
    session: ConnectionSession,
    onSecureScreen: (Boolean) -> Unit,
    openTarget: NotificationOpenPayload?,
    onOpenTargetHandled: () -> Unit,
    onConnect: (Long) -> Unit,
) {
    val navController = rememberNavController()
    val sessionReady by session.ready.collectAsStateWithLifecycle()
    val connectionState by session.state.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.let { entry ->
        val destination = entry.destination
        when {
            destination.hasRoute<EditConnection>() -> entry.toRoute<EditConnection>()
            destination.hasRoute<Keys>() -> Keys
            destination.hasRoute<Terminal>() -> entry.toRoute<Terminal>()
            else -> Connections
        }
    } ?: Connections
    val connectionsEntry = currentEntry?.let {
        runCatching { navController.getBackStackEntry<Connections>() }.getOrNull()
    }
    val connectionsViewModel = connectionsEntry?.let { hiltViewModel<ConnectionsViewModel>(it) }
    val connectionsState = connectionsViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    SideEffect { onSecureScreen(secureWindowRequired(currentDestination, connectionState)) }

    LaunchedEffect(openTarget, sessionReady, connectionsViewModel, connectionState) {
        val payload = openTarget ?: return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val messageViewModel = connectionsViewModel ?: return@LaunchedEffect
        val pendingRouteId = when (val current = connectionState) {
            is ConnectionState.Connecting -> current.routeId
            is ConnectionState.Reconnecting -> current.routeId
            else -> null
        }
        if (pendingRouteId == payload.identifiers.routeId) return@LaunchedEffect
        val resolution = payload.identifiers.resolve(connectionState)
        val livePane = resolution.livePane(connectionState)
        if (livePane == null) {
            messageViewModel.showOpenMessage(OpenTargetIdentifiers.STALE_MESSAGE)
            navController.popBackStack<Connections>(inclusive = false)
        } else {
            navController.navigate(livePane.toTerminal(resolution.level.takeIf { resolution.message != null })) {
                popUpTo<Connections> { inclusive = false }
                launchSingleTop = true
            }
        }
        onOpenTargetHandled()
    }

    NavHost(
        navController = navController,
        startDestination = Connections,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable<Connections> {
            ConnectionsRoute(
                onEdit = { navController.navigate(EditConnection(it)) },
                onDuplicate = { navController.navigate(EditConnection(it, duplicate = true)) },
                onKeys = { navController.navigate(Keys) },
                onTerminal = { navController.navigate(Terminal(it)) },
                onConnect = onConnect,
            )
        }
        composable<EditConnection> { entry ->
            ConnectionEditorRoute(
                routeId = entry.toRoute<EditConnection>().routeId,
                createdKeyResults = entry.savedStateHandle,
                onBack = { navController.popBackStack() },
                onCreateKey = { navController.navigate(Keys) },
            )
        }
        composable<Keys> {
            val previous = navController.previousBackStackEntry
            val returnsToEditor = previous?.destination?.hasRoute<EditConnection>() == true
            KeysRoute(
                backLabel = if (returnsToEditor) "Back to connection" else "Back to connections",
                returnCreatedKey = returnsToEditor,
                onBack = { navController.popBackStack() },
                onCreatedKey = { id ->
                    if (returnsToEditor) {
                        previous.savedStateHandle[CREATED_KEY_ID] = id
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<Terminal> { entry ->
            val destination = entry.toRoute<Terminal>()
            val target = destination.toIdentifiers()
            val resolution = target.resolve(connectionState)
            val livePane = resolution.takeIf { it.message == null }?.livePane(connectionState)
            val validation = viewModel<TerminalEntryValidation>(viewModelStoreOwner = entry)
            SideEffect { if (livePane != null) validation.succeeded = true }
            val sameRoute = when (val current = connectionState) {
                is ConnectionState.Connected -> current.routeId
                is ConnectionState.Reconnecting -> current.routeId
                is ConnectionState.Connecting -> current.routeId
                else -> null
            } == destination.routeId
            if (destination.hasHierarchyTarget && livePane == null && (!validation.succeeded || !sameRoute)) {
                val messageViewModel = connectionsViewModel
                    ?: hiltViewModel<ConnectionsViewModel>(navController.getBackStackEntry<Connections>())
                LaunchedEffect(destination) {
                    messageViewModel.showOpenMessage(OpenTargetIdentifiers.STALE_MESSAGE)
                    navController.popBackStack<Connections>(inclusive = false)
                }
            } else {
                TerminalRoute(
                    routeName = connectionsState?.routes?.firstOrNull { it.id == destination.routeId }?.name ?: "Herdr",
                    connectionState = connectionState,
                    target = target.takeIf { destination.hasHierarchyTarget },
                    initialResolution = destination.initialResolution(),
                    onBack = { navController.popBackStack<Connections>(inclusive = false) },
                )
            }
        }
    }
}

internal class TerminalEntryValidation : ViewModel() {
    var succeeded = false
}

private val Terminal.hasHierarchyTarget
    get() = sessionId != null || workspaceId != null || tabId != null || paneId != null || epoch != null || incarnation != null

private fun OpenTargetIdentifiers.toTerminal(openLevel: OpenLevel? = null) = Terminal(
    routeId,
    sessionId,
    workspaceId,
    tabId,
    paneId,
    epoch,
    incarnation,
    openLevel?.name,
)

private fun Terminal.initialResolution(): OpenResolution? = when (openLevel) {
    OpenLevel.Sessions.name -> OpenResolution(OpenLevel.Sessions, message = OpenTargetIdentifiers.STALE_MESSAGE)
    OpenLevel.Workspaces.name -> OpenResolution(OpenLevel.Workspaces, sessionId, message = OpenTargetIdentifiers.STALE_MESSAGE)
    OpenLevel.Tabs.name -> OpenResolution(OpenLevel.Tabs, sessionId, workspaceId, message = OpenTargetIdentifiers.STALE_MESSAGE)
    OpenLevel.Panes.name -> OpenResolution(OpenLevel.Panes, sessionId, workspaceId, tabId, message = OpenTargetIdentifiers.STALE_MESSAGE)
    else -> null
}
