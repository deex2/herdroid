package dev.herdroid.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.ServiceTestRule
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.herdroid.MainActivity
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.EndpointAuthenticationInput
import dev.herdroid.core.data.EndpointWriteInput
import dev.herdroid.core.data.ProcessDatabaseState
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteWriteInput
import dev.herdroid.session.impl.ConnectionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class ProcessDatabaseLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()

    @Test
    fun processGraphOpensOneDatabaseAndAttemptsCannotCloseIt() = runBlocking {
        val entryPoint = EntryPointAccessors.fromApplication(context, ProcessDatabaseEntryPoint::class.java)
        val identity = entryPoint.databaseState()
        val routeStore = entryPoint.routeRepository()
        val connectionRoutes = entryPoint.connectionRouteRepository()
        assertSame(routeStore, connectionRoutes)
        val routeId = RouteWriteInput(
            id = 0,
            name = "process-owned",
            target = EndpointWriteInput(
                "127.0.0.1",
                22,
                "test",
                EndpointAuthenticationInput.Password("test-only".encodeToByteArray()),
                null,
            ),
            jump = null,
        ).use { routeStore.save(it) }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            (serviceRule.bindService(Intent(context, ConnectionService::class.java)) as ConnectionService.LocalBinder).disconnect()

            assertSame(identity, entryPoint.databaseState())
            assertSame(routeStore, entryPoint.routeRepository())
            assertSame(connectionRoutes, entryPoint.connectionRouteRepository())
            assertTrue(routeStore.routes.first().isNotEmpty())
            connectionRoutes.loadForConnection(routeId).close()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ProcessDatabaseEntryPoint {
    fun databaseState(): ProcessDatabaseState
    fun routeRepository(): RouteRepository
    fun connectionRouteRepository(): ConnectionRouteRepository
}
