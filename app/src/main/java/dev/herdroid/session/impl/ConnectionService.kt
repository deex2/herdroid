package dev.herdroid.session.impl

import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.data.BridgeLaunchCache
import dev.herdroid.core.data.ConnectionAuthenticationInput
import dev.herdroid.core.data.ConnectionEndpointInput
import dev.herdroid.core.data.ConnectionRouteInput
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.RouteNotFoundException
import dev.herdroid.core.herdr.BridgeArtifactCatalog
import dev.herdroid.core.herdr.BridgeClient
import dev.herdroid.core.herdr.BridgeExpectation
import dev.herdroid.core.herdr.BridgeInstaller
import dev.herdroid.core.herdr.BridgeLaunchDescriptor
import dev.herdroid.core.herdr.DiscoveryResult
import dev.herdroid.core.herdr.HerdrActions
import dev.herdroid.core.herdr.TerminalClient
import dev.herdroid.core.herdr.VerifiedInstall
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.core.ssh.ConnectedRouteBridgeTransport
import dev.herdroid.core.ssh.SshAuthenticationInput
import dev.herdroid.core.ssh.SshConnectionInput
import dev.herdroid.core.ssh.SshConnector
import dev.herdroid.core.ssh.SshEndpointInput
import dev.herdroid.navigation.AppNotificationTargetIntentFactory
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionOwnershipMode
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.TerminalLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ConnectionService : Service() {
    @Inject internal lateinit var routes: ConnectionRouteRepository
    @Inject internal lateinit var sshConnector: SshConnector
    @Inject @field:Dispatcher(HerdroidDispatchers.IO)
    internal lateinit var ioDispatcher: CoroutineDispatcher
    @Inject @field:Dispatcher(HerdroidDispatchers.Default)
    internal lateinit var defaultDispatcher: CoroutineDispatcher
    @Inject internal lateinit var bridgeCatalogLoader: BridgeCatalogLoader
    @Inject internal lateinit var notificationTargetIntentFactory: AppNotificationTargetIntentFactory
    private val machine = ConnectionStateMachine()
    private lateinit var scope: CoroutineScope
    private lateinit var terminalReleaseScope: CoroutineScope
    private val binder = LocalBinder()
    private val activityBound = MutableStateFlow(true)
    private val serviceCommands = Mutex()
    private lateinit var owner: ConnectionOwner
    private lateinit var ownership: ServiceOwnership
    private lateinit var notifications: NotificationController
    private lateinit var notificationOwner: ServiceNotificationOwner
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val registrationMonitor = Any()
    private var registration: ServiceRegistration? = null

    inner class LocalBinder : Binder(), ServiceEndpoint {
        private val hierarchy by lazy { HerdrHierarchyCommands(owner::herdrActions) }

        override fun registered(registration: ServiceRegistration) = register(registration)
        override fun disconnect() { this@ConnectionService.disconnect() }
        override fun approveTrust(accept: Boolean) = owner.approveTrust(accept)
        override fun approveHostKeyReset(accept: Boolean) = owner.approveHostKeyReset(accept)
        override fun approveInstall(accept: Boolean) = owner.approveBridgeInstall(accept)
        override suspend fun attachTerminal(
            sessionId: String,
            paneId: String,
            cols: Int,
            rows: Int,
            takeover: Boolean,
        ): TerminalLease? = deliverTerminalLease(ioDispatcher) {
            owner.attachTerminal(sessionId, paneId, cols, rows, takeover)
        }

        override suspend fun createWorkspace(
            sessionId: String,
            cwd: String?,
            label: String?,
            env: Map<String, String>,
        ) = hierarchy.createWorkspace(sessionId, cwd, label, env)

        override suspend fun focusWorkspace(sessionId: String, workspaceId: String) =
            hierarchy.focusWorkspace(sessionId, workspaceId)

        override suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String) =
            hierarchy.renameWorkspace(sessionId, workspaceId, label)

        override suspend fun closeWorkspace(sessionId: String, workspaceId: String) =
            hierarchy.closeWorkspace(sessionId, workspaceId)

        override suspend fun createTab(
            sessionId: String,
            workspaceId: String?,
            cwd: String?,
            label: String?,
            env: Map<String, String>,
        ) = hierarchy.createTab(sessionId, workspaceId, cwd, label, env)

        override suspend fun focusTab(sessionId: String, tabId: String) = hierarchy.focusTab(sessionId, tabId)
        override suspend fun renameTab(sessionId: String, tabId: String, label: String) =
            hierarchy.renameTab(sessionId, tabId, label)

        override suspend fun closeTab(sessionId: String, tabId: String) = hierarchy.closeTab(sessionId, tabId)
        override suspend fun focusPane(sessionId: String, paneId: String) = hierarchy.focusPane(sessionId, paneId)
        override suspend fun splitPane(
            sessionId: String,
            paneId: String,
            direction: SplitDirection,
            ratio: Double?,
            cwd: String?,
            env: Map<String, String>,
        ) = hierarchy.splitPane(sessionId, paneId, direction, ratio, cwd, env)

        override suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode) =
            hierarchy.zoomPane(sessionId, paneId, mode)

        override suspend fun renamePane(sessionId: String, paneId: String, label: String) =
            hierarchy.renamePane(sessionId, paneId, label)

        override suspend fun closePane(sessionId: String, paneId: String) = hierarchy.closePane(sessionId, paneId)
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        terminalReleaseScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        ownership = ServiceOwnership(
            stopStarted = { startId -> stopSelfResult(startId) },
            disconnectBound = { disconnect() },
            stopTerminal = { startId -> stopOwnedService(startId) },
        )
        notifications = NotificationController(this, notificationTargetIntentFactory)
        notificationOwner = ServiceNotificationOwner(notifications) { activityBound.value = it }
        owner = ConnectionOwner(
            scope,
            machine,
            productionDependencies(),
            ::stopAfterOwnerTerminal,
            terminalReleaseScope,
        )
        scope.launch {
            combine(machine.state, machine.diagnostics, activityBound) { state, diagnostics, bound ->
                publication(state, diagnostics, bound)
            }.collect(::publish)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        ownership.bound()
        notifications.visible(true)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        notifications.visible(false)
        ownership.unbound()
        return false
    }

    override fun onDestroy() {
        if (::notificationOwner.isInitialized) notificationOwner.stopped()
        if (::owner.isInitialized) {
            requestServiceOwnerShutdown(owner, scope, terminalReleaseScope)
        } else {
            if (::terminalReleaseScope.isInitialized) terminalReleaseScope.cancel()
            if (::scope.isInitialized) scope.cancel()
        }
        unregisterNetworkCallback()
        if (::ownership.isInitialized && ownership.foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        if (::ownership.isInitialized) ownership.stopped()
        synchronized(registrationMonitor) { registration.also { registration = null } }?.close()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val routeId = intent?.getLongExtra(EXTRA_ROUTE_ID, 0) ?: 0
        if (routeId != 0L) {
            ownership.started(startId)
            connectFromVisibleActivity(routeId, startId)
        }
        return START_NOT_STICKY
    }

    private fun connectFromVisibleActivity(routeId: Long, startId: Int) = notificationOwner.replace(scope) { lease ->
        serviceCommands.withLock {
            owner.connect(routeId, startId).join()
            if (!ownership.isCurrent(startId)) return@withLock
            val promoted = startForegroundIfAllowed()
            val foreground = ownership.promoted(startId, promoted) && promoted
            notificationOwner.foregroundResolved(
                lease,
                foreground,
                machine.state.value as? ConnectionState.Connected,
            )
            machine.diagnostic(
                if (foreground) "Connection ownership: foreground" else "Connection ownership: activity-bound",
            )
            registerNetworkCallback()
        }
    }

    private fun disconnect() = notificationOwner.disconnect(scope, owner::cancel) {
        serviceCommands.withLock {
            owner.shutdown()
            stopOwnedService()
        }
    }

    private fun stopAfterOwnerTerminal(startId: Int) {
        notificationOwner.terminal(scope) {
            serviceCommands.withLock {
                if (machine.state.value is ConnectionState.Failed || machine.state.value == ConnectionState.Disconnected) {
                    owner.release()
                    ownership.terminal(startId)
                }
            }
        }
    }

    private fun stopOwnedService(startId: Int? = null) {
        notificationOwner.stopped()
        if (ownership.foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        ownership.stopped()
        activityBound.value = true
        unregisterNetworkCallback()
        if (startId == null) stopSelf() else stopSelfResult(startId)
    }

    private fun productionDependencies() = ConnectionDependencies(
        findRoute = { routeId ->
            try {
                routes.loadForConnection(routeId)
            } catch (_: RouteNotFoundException) {
                null
            }
        },
        knownHosts = routes::knownHosts,
        updateKnownHost = routes::updateKnownHost,
        deleteKnownHost = routes::deleteKnownHost,
        clearBridgeCache = { routes.updateBridgeCache(it, null) },
        connectRoute = { routeInput, targetKnownHosts, jumpKnownHosts ->
            val routeId = routeInput.routeId
            val routeName = routeInput.routeName
            val herdrPath = routeInput.target.herdrPath
            val bridgeCache = routeInput.target.bridgeCache
            val connected = sshConnector.connect(routeInput.toSshInput(), targetKnownHosts, jumpKnownHosts)

            suspend fun loadCatalog(): BridgeArtifactCatalog = try {
                bridgeCatalogLoader.load()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                throw TerminalConnectionFailure("bridge_unavailable", "Trusted bridge assets are unavailable")
            }

            suspend fun startBridge(
                installer: BridgeInstaller,
                catalog: BridgeArtifactCatalog,
                descriptor: BridgeLaunchDescriptor,
                timeoutMillis: Long = 15_000,
            ): ConnectionActiveBridge {
                val client = BridgeClient.start(
                    connected.exec(installer.launchCommand(descriptor)),
                    BridgeExpectation(
                        descriptor.os,
                        descriptor.architecture,
                        catalog.pluginVersion,
                        catalog.minHerdrVersion,
                    ),
                    ioDispatcher,
                    timeoutMillis,
                )
                return ConnectionActiveBridge(
                    collectUntilFailure = { publish ->
                        coroutineScope {
                            val coordinator = notificationOwner.coordinator(client, this, routeId)
                            launch { coordinator.state.collect { publish(it.sessions) } }
                            throw client.failure.filterNotNull().first()
                        }
                    },
                    close = client::close,
                    actions = HerdrActions(client),
                    attachTerminal = { session, pane, cols, rows, takeover ->
                        TerminalClient.attach(
                            connected,
                            descriptor.os,
                            descriptor.herdrPath,
                            session,
                            pane,
                            cols,
                            rows,
                            ioDispatcher,
                            defaultDispatcher,
                            takeover,
                        )
                    },
                )
            }

            ConnectionRouteAttempt(
                prepareBridge = {
                    val catalog = loadCatalog()
                    val installer = BridgeInstaller(ConnectedRouteBridgeTransport(connected, ioDispatcher), catalog)
                    val discovery = installer.discover(herdrPath) as? DiscoveryResult.Ready
                        ?: throw TerminalConnectionFailure("herdr_missing", "Herdr was not found on the selected route")
                    val installPlan = installer.preview(routeName, discovery.os, discovery.architecture)
                    val preview = installPlan.approval
                    var verified: VerifiedInstall? = null
                    ConnectionBridgePlan(
                        preview = preview,
                        verifyExisting = {
                            installer.verifyExisting(installPlan, discovery.herdrPath).also { verified = it } != null
                        },
                        install = { verified = installer.install(installPlan, discovery.herdrPath) },
                        start = {
                            val install = requireNotNull(verified) { "Bridge installation was not verified" }
                            val descriptor = installer.launchDescriptor(install)
                            val active = startBridge(installer, catalog, descriptor)
                            try {
                                routes.updateBridgeCache(
                                    routeId,
                                    BridgeLaunchCache(descriptor.target, descriptor.herdrPath, descriptor.bridgePath),
                                )
                                active
                            } catch (failure: Throwable) {
                                active.close()
                                throw failure
                            }
                        },
                    )
                },
                close = connected::close,
                startCachedBridge = bridgeCache?.let { cache ->
                    suspend {
                        val catalog = loadCatalog()
                        val installer = BridgeInstaller(ConnectedRouteBridgeTransport(connected, ioDispatcher), catalog)
                        val descriptor = installer.cachedLaunchDescriptor(
                            cache.target,
                            cache.herdrPath,
                            cache.bridgePath,
                        )
                        if (descriptor == null) {
                            routes.updateBridgeCache(routeId, null)
                            null
                        } else {
                            try {
                                startBridge(installer, catalog, descriptor, 5_000)
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Throwable) {
                                routes.updateBridgeCache(routeId, null)
                                null
                            }
                        }
                    }
                },
            )
        },
        waitForRetry = { seconds, wake -> waitForRetry(seconds, wake) },
    )

    private fun ConnectionRouteInput.toSshInput(): SshConnectionInput {
        val targetInput = target.toSshInput()
        var jumpInput: SshEndpointInput? = null
        return try {
            jumpInput = jump?.toSshInput()
            SshConnectionInput(routeName, targetInput, jumpInput)
        } catch (failure: Throwable) {
            jumpInput?.close()
            targetInput.close()
            throw failure
        }
    }

    private fun ConnectionEndpointInput.toSshInput(): SshEndpointInput {
        val connectorAuthentication = when (val source = authentication) {
            is ConnectionAuthenticationInput.Password ->
                SshAuthenticationInput.Password(source.moveToConnector())
            is ConnectionAuthenticationInput.HardwareKey -> {
                val publicKey = source.copyPublicKeyForConnection()
                try {
                    SshAuthenticationInput.HardwareKey(source.keyId, source.alias, publicKey)
                } finally {
                    publicKey.fill(0)
                }
            }
        }
        return try {
            SshEndpointInput(hostname, port, username, connectorAuthentication, herdrPath)
        } catch (failure: Throwable) {
            connectorAuthentication.close()
            throw failure
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForRetry(seconds: Long, wake: kotlinx.coroutines.channels.ReceiveChannel<Unit>) {
        select<Unit> {
            wake.onReceive { }
            onTimeout(seconds * 1_000) { }
        }
    }

    private fun startForegroundIfAllowed(): Boolean {
        if (!notificationsAllowed()) return false
        return try {
            notifications.createChannels()
            startForeground(
                NotificationController.CONNECTION_NOTIFICATION_ID,
                notifications.connectionNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    private fun register(next: ServiceRegistration) {
        val previous = synchronized(registrationMonitor) {
            registration.also { registration = next }
        }
        if (previous !== next) previous?.close()
        next.publish(publication(machine.state.value, machine.diagnostics.value, activityBound.value))
    }

    private fun publish(publication: SessionPublication) {
        synchronized(registrationMonitor) { registration }?.publish(publication)
    }

    private fun publication(
        state: ConnectionState,
        diagnostics: List<ConnectionDiagnostic>,
        activityBound: Boolean,
    ) = SessionPublication(
        connection = state,
        diagnostics = diagnostics,
        ownership = if (activityBound) ConnectionOwnershipMode.ActivityBound else ConnectionOwnershipMode.Foreground,
    )

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = owner.networkAvailable()
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // Bounded timeout retries remain available without the platform wakeup.
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // It may already be unregistered during process teardown.
        }
    }

    internal companion object {
        const val EXTRA_ROUTE_ID = "route_id"

        fun requestConnect(context: Context, routeId: Long) {
            val intent = Intent(context, ConnectionService::class.java).putExtra(EXTRA_ROUTE_ID, routeId)
            if (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            ) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

internal fun requestServiceOwnerShutdown(
    owner: ConnectionOwner,
    ownerScope: CoroutineScope,
    terminalReleaseScope: CoroutineScope,
) {
    owner.cancel()
    terminalReleaseScope.launch {
        try {
            owner.shutdown()
        } finally {
            ownerScope.cancel()
            terminalReleaseScope.cancel()
        }
    }
}

internal suspend fun deliverTerminalLease(
    dispatcher: CoroutineDispatcher,
    attach: suspend () -> TerminalLease?,
): TerminalLease? {
    var lease: TerminalLease? = null
    return try {
        withContext(dispatcher) { attach().also { lease = it } }
    } catch (failure: Throwable) {
        runCatching { lease?.close() }
        throw failure
    }
}
