package dev.herdroid.feature.connections

import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.testing.FakeConnectionSession
import dev.herdroid.core.testing.FakeRouteStore
import dev.herdroid.core.testing.MainDispatcherRule
import dev.herdroid.core.testing.testRouteSummary
import dev.herdroid.session.api.ConnectionOwnershipMode
import dev.herdroid.session.api.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `projects route session ownership and local data availability`() = runTest {
        val routes = FakeRouteStore(listOf(testRouteSummary(id = 7, name = "office")))
        val session = FakeConnectionSession()
        val availability = MutableStateFlow<LocalDataAvailability>(LocalDataAvailability.Initializing)
        session.publishState(ConnectionState.Connected(7, emptyMap()))
        session.publishOwnership(ConnectionOwnershipMode.Foreground)
        val viewModel = ConnectionsViewModel(routes, session, availability)
        backgroundScope.launch { viewModel.uiState.collect {} }

        advanceUntilIdle()
        assertEquals(LocalDataAvailability.Initializing, viewModel.uiState.value.availability)

        availability.value = LocalDataAvailability.Available
        advanceUntilIdle()
        assertEquals(LocalDataAvailability.Available, viewModel.uiState.value.availability)
        assertEquals(listOf("office"), viewModel.uiState.value.routes.map { it.name })
        assertEquals(ConnectionState.Connected(7, emptyMap()), viewModel.uiState.value.connectionState)
        assertEquals(false, viewModel.uiState.value.activityBound)

        availability.value = LocalDataAvailability.Unavailable
        advanceUntilIdle()
        assertEquals(LocalDataAvailability.Unavailable, viewModel.uiState.value.availability)
    }

    @Test
    fun `forwards every connection command through the session contract`() {
        val session = FakeConnectionSession()
        val viewModel = ConnectionsViewModel(
            FakeRouteStore(),
            session,
            MutableStateFlow(LocalDataAvailability.Available),
        )

        viewModel.connect(9)
        viewModel.disconnect()
        viewModel.approveTrust(true)
        viewModel.approveHostKeyReset(false)
        viewModel.approveBridgeInstall(true)

        assertEquals(listOf(9L), session.connectedRouteIds)
        assertEquals(1, session.disconnectCalls.get())
        assertEquals(listOf(true), session.trustApprovals)
        assertEquals(listOf(false), session.hostKeyResetApprovals)
        assertEquals(listOf(true), session.installApprovals)
    }

    @Test
    fun `deleting the active route disconnects and removes it`() = runTest {
        val routes = FakeRouteStore(listOf(testRouteSummary(id = 7)))
        val session = FakeConnectionSession().apply {
            publishState(ConnectionState.Connected(7, emptyMap()))
        }
        val viewModel = ConnectionsViewModel(
            routes,
            session,
            MutableStateFlow(LocalDataAvailability.Available),
        )

        viewModel.delete(7).join()

        assertEquals(1, session.disconnectCalls.get())
        assertEquals(emptyList<Any>(), routes.routes.value)
    }
}
