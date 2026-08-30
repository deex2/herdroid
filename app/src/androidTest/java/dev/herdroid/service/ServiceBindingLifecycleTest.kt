package dev.herdroid.service

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.KeyMetadataRepository
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.di.RepositoryModule
import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.core.testing.FakeKeyMetadataRepository
import dev.herdroid.core.testing.FakeRouteStore
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.api.TerminalLease
import dev.herdroid.session.impl.ProcessSessionBridge
import dev.herdroid.session.impl.ServiceBindingViewModel
import dev.herdroid.session.impl.ServiceEndpoint
import dev.herdroid.session.impl.ServiceRegistration
import dev.herdroid.session.impl.SessionPublication
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
@UninstallModules(RepositoryModule::class)
class ServiceBindingLifecycleTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val routes = FakeRouteStore()
    private val keyMetadata = FakeKeyMetadataRepository()

    @BindValue @JvmField internal val routeRepository: RouteRepository = routes
    @BindValue @JvmField internal val connectionRouteRepository: ConnectionRouteRepository = routes
    @BindValue @JvmField internal val keyMetadataRepository: KeyMetadataRepository = keyMetadata
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var processBridge: ProcessSessionBridge
    @Inject lateinit var connectionSession: ConnectionSession

    @Test
    fun connectBeforeRegistrationStartsAndReachesRealOwnerExactlyOnce() = runBlocking {
        hilt.inject()
        val session = connectionSession
        val viewModel = RealServiceBindingViewModel(application, processBridge)
        val routeId = Long.MAX_VALUE

        try {
            assertEquals(false, session.ready.value)
            session.connect(routeId)
            viewModel.onActivityStart()

            withTimeout(5_000) {
                processBridge.snapshot.first {
                    it.serviceEpoch != null &&
                        (it.connection as? ConnectionState.Failed)?.code == "route_missing"
                }
            }
            assertEquals(true, session.ready.value)
            viewModel.onActivityStop(changingConfigurations = false)
            viewModel.onActivityStart()
            delay(250)

            assertEquals(1, routes.connectionLoadCalls.get())
        } finally {
            viewModel.clear()
        }
    }

    @Test
    fun rotationRetainsBindingAndRealStopUnbindsImmediately() {
        val bridge = ProcessSessionBridge()
        val endpoint = Endpoint()
        val viewModel = ExposedServiceBindingViewModel(
            ApplicationProvider.getApplicationContext(),
            bridge,
            endpoint,
        )

        viewModel.onActivityStart()
        val epoch = endpoint.registrations.single().epoch
        viewModel.onActivityStop(changingConfigurations = true)
        viewModel.onActivityStart()

        assertEquals(1, viewModel.bindCalls)
        assertEquals(0, viewModel.unbindCalls)
        assertEquals(1, endpoint.registrations.size)
        assertEquals(epoch, bridge.snapshot.value.serviceEpoch)

        viewModel.onActivityStop(changingConfigurations = false)
        assertEquals(1, viewModel.unbindCalls)

        viewModel.onActivityStart()
        assertEquals(2, viewModel.bindCalls)
        assertEquals(2, endpoint.registrations.size)
        assertSame(endpoint.registrations.first(), endpoint.registrations.last())
        assertEquals(epoch, bridge.snapshot.value.serviceEpoch)

        viewModel.clear()
        assertEquals(2, viewModel.unbindCalls)
    }

    @Test
    fun abnormalBinderLossClosesOnlyItsRegistrationAndReconnectAllocatesANewEpoch() {
        val bridge = ProcessSessionBridge()
        val endpoint = Endpoint()
        val viewModel = ExposedServiceBindingViewModel(
            ApplicationProvider.getApplicationContext(),
            bridge,
            endpoint,
        )
        viewModel.onActivityStart()
        val oldEpoch = bridge.snapshot.value.serviceEpoch

        viewModel.disconnectBinder()
        assertEquals(SessionPublication(), bridge.snapshot.value)

        viewModel.reconnectBinder()
        assertEquals(1, viewModel.bindCalls)
        assertEquals(2, endpoint.registrations.size)
        assertEquals(false, oldEpoch == bridge.snapshot.value.serviceEpoch)
        viewModel.clear()
    }

    @Test
    fun bindingDeathRebindsOnceAndStaleCallbackCannotClearReplacement() {
        val bridge = ProcessSessionBridge()
        val endpoint = Endpoint()
        val viewModel = ExposedServiceBindingViewModel(
            ApplicationProvider.getApplicationContext(),
            bridge,
            endpoint,
        )
        viewModel.onActivityStart()
        val oldEpoch = bridge.snapshot.value.serviceEpoch

        viewModel.bindingDied(0)

        assertEquals(2, viewModel.bindCalls)
        assertEquals(1, viewModel.unbindCalls)
        assertEquals(2, endpoint.registrations.size)
        val replacementEpoch = bridge.snapshot.value.serviceEpoch
        assertEquals(false, oldEpoch == replacementEpoch)

        viewModel.disconnectBinder(0)
        assertEquals(replacementEpoch, bridge.snapshot.value.serviceEpoch)

        viewModel.clear()
        assertEquals(2, viewModel.unbindCalls)
    }

    private class ExposedServiceBindingViewModel(
        application: Application,
        bridge: ProcessSessionBridge,
        private val endpoint: Endpoint,
    ) : ServiceBindingViewModel(application, bridge) {
        var bindCalls = 0
        var unbindCalls = 0
        private val connections = mutableListOf<ServiceConnection>()

        override fun bindService(connection: ServiceConnection): Boolean {
            bindCalls++
            connections += connection
            connection.onServiceConnected(ComponentName("dev.herdroid", "ConnectionService"), endpoint)
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            unbindCalls++
        }

        fun disconnectBinder(index: Int = connections.lastIndex) =
            connections[index].onServiceDisconnected(ComponentName("dev.herdroid", "ConnectionService"))

        fun reconnectBinder() =
            connections.last().onServiceConnected(ComponentName("dev.herdroid", "ConnectionService"), endpoint)

        fun bindingDied(index: Int) =
            connections[index].onBindingDied(ComponentName("dev.herdroid", "ConnectionService"))

        fun clear() = onCleared()
    }

    private class RealServiceBindingViewModel(
        application: Application,
        bridge: ProcessSessionBridge,
    ) : ServiceBindingViewModel(application, bridge) {
        fun clear() = onCleared()
    }

    private class Endpoint : Binder(), ServiceEndpoint {
        val registrations = mutableListOf<ServiceRegistration>()
        override fun registered(registration: ServiceRegistration) { registrations += registration }
        override fun disconnect() = Unit
        override fun approveTrust(accept: Boolean) = Unit
        override fun approveHostKeyReset(accept: Boolean) = Unit
        override fun approveInstall(accept: Boolean) = Unit
        override suspend fun attachTerminal(
            sessionId: String,
            paneId: String,
            cols: Int,
            rows: Int,
            takeover: Boolean,
    ): TerminalLease? = null

        override suspend fun createWorkspace(
            sessionId: String,
            cwd: String?,
            label: String?,
            env: Map<String, String>,
        ) = ActionOutcome.Succeeded

        override suspend fun focusWorkspace(sessionId: String, workspaceId: String) = ActionOutcome.Succeeded
        override suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String) = ActionOutcome.Succeeded
        override suspend fun closeWorkspace(sessionId: String, workspaceId: String) = ActionOutcome.Succeeded
        override suspend fun createTab(
            sessionId: String,
            workspaceId: String?,
            cwd: String?,
            label: String?,
            env: Map<String, String>,
        ) = ActionOutcome.Succeeded

        override suspend fun focusTab(sessionId: String, tabId: String) = ActionOutcome.Succeeded
        override suspend fun renameTab(sessionId: String, tabId: String, label: String) = ActionOutcome.Succeeded
        override suspend fun closeTab(sessionId: String, tabId: String) = ActionOutcome.Succeeded
        override suspend fun focusPane(sessionId: String, paneId: String) = ActionOutcome.Succeeded
        override suspend fun splitPane(
            sessionId: String,
            paneId: String,
            direction: SplitDirection,
            ratio: Double?,
            cwd: String?,
            env: Map<String, String>,
        ) = ActionOutcome.Succeeded

        override suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode) = ActionOutcome.Succeeded
        override suspend fun renamePane(sessionId: String, paneId: String, label: String) = ActionOutcome.Succeeded
        override suspend fun closePane(sessionId: String, paneId: String) = ActionOutcome.Succeeded
    }
}
