package dev.herdroid.session.impl

import dev.herdroid.session.api.*

import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessSessionBridgeTest {
    @Test
    fun staleServiceCannotPublishOrClearNewService() {
        val bridge = ProcessSessionBridge()
        val old = bridge.register(Endpoint())
        val current = bridge.register(Endpoint())

        current.publish(connected(routeId = 2L))
        old.publish(connected(routeId = 1L))
        old.close()

        assertEquals(current.epoch, bridge.snapshot.value.serviceEpoch)
        assertEquals(2L, (bridge.snapshot.value.connection as ConnectionState.Connected).routeId)
    }

    @Test
    fun replacementLinearizesCommandsExactlyOnce() = runTest {
        val bridge = ProcessSessionBridge()
        val old = Endpoint()
        bridge.register(old)
        val replacement = Endpoint()
        bridge.register(replacement)

        bridge.focusPane("session", "pane")

        assertEquals(0, old.focusCalls)
        assertEquals(1, replacement.focusCalls)
    }

    @Test
    fun reservedSynchronousCommandCompletesOnceOnRetiredEndpoint() {
        val bridge = ProcessSessionBridge()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = object : Endpoint() {
            override fun disconnect() {
                disconnectCalls++
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                registration.publish(connected(1L))
            }
        }
        bridge.register(old).also(old::registered)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val command = executor.submit { bridge.disconnect() }
            check(entered.await(5, TimeUnit.SECONDS))
            val replacement = Endpoint()
            val current = bridge.register(replacement)
            current.publish(connected(2L))
            release.countDown()
            command.get(5, TimeUnit.SECONDS)

            assertEquals(1, old.disconnectCalls)
            assertEquals(0, replacement.disconnectCalls)
            assertEquals(current.epoch, bridge.snapshot.value.serviceEpoch)
            assertEquals(2L, (bridge.snapshot.value.connection as ConnectionState.Connected).routeId)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun reservedHierarchyCommandCompletesOnceOnRetiredEndpoint() = runTest {
        val bridge = ProcessSessionBridge()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = object : Endpoint() {
            override suspend fun focusPane(sessionId: String, paneId: String): ActionOutcome {
                focusCalls++
                entered.complete(Unit)
                release.await()
                registration.publish(connected(1L))
                return ActionOutcome.Succeeded
            }
        }
        bridge.register(old).also(old::registered)
        val command = async { bridge.focusPane("session", "pane") }
        entered.await()
        val replacement = Endpoint()
        val current = bridge.register(replacement)
        current.publish(connected(2L))
        release.complete(Unit)

        assertSame(ActionOutcome.Succeeded, command.await())
        assertEquals(1, old.focusCalls)
        assertEquals(0, replacement.focusCalls)
        assertEquals(current.epoch, bridge.snapshot.value.serviceEpoch)
    }

    @Test
    fun reservedTerminalAttachmentCompletesOnceOnRetiredEndpoint() = runTest {
        val bridge = ProcessSessionBridge()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lease = TestLease()
        val old = object : Endpoint() {
            override suspend fun attachTerminal(
                sessionId: String,
                paneId: String,
                cols: Int,
                rows: Int,
                takeover: Boolean,
            ): TerminalLease? {
                attachCalls++
                entered.complete(Unit)
                release.await()
                registration.publish(connected(1L))
                return lease
            }
        }
        bridge.register(old).also(old::registered)
        val command = async { bridge.attachTerminal("session", "pane", 80, 24, false) }
        entered.await()
        val replacement = Endpoint()
        val current = bridge.register(replacement)
        current.publish(connected(2L))
        release.complete(Unit)

        assertNull(command.await())
        assertEquals(1, lease.closeCount)
        assertEquals(1, old.attachCalls)
        assertEquals(0, replacement.attachCalls)
        assertEquals(current.epoch, bridge.snapshot.value.serviceEpoch)
    }

    @Test
    fun cancelledBinderDeliveryClosesProducedTerminalLeaseOnce() = runTest {
        val directCancellation = CancellationException("direct cancellation")
        val direct = try {
            deliverTerminalLease(Dispatchers.Unconfined) { throw directCancellation }
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertSame(directCancellation, direct)

        val lease = TestLease()
        val cancellation = CancellationException("cancelled delivery")
        val failure = try {
            deliverTerminalLease(Dispatchers.Default) {
                currentCoroutineContext().cancel(cancellation)
                lease
            }
            null
        } catch (failure: Throwable) {
            failure
        }

        assertEquals(CancellationException::class.java, failure?.javaClass)
        assertEquals(cancellation.message, failure?.message)
        assertEquals(1, lease.closeCount)
    }

    private class TestLease : TerminalLease {
        var closeCount = 0
        override val state = MutableStateFlow<TerminalState>(TerminalState.Attaching)
        override val frames: Flow<TerminalFrame> = emptyFlow()
        override fun sendText(text: String) = Unit
        override fun sendBytes(bytes: ByteArray) = Unit
        override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) = Unit
        override fun scroll(
            direction: TerminalScrollDirection,
            lines: Int,
            source: TerminalScrollSource,
            column: Int?,
            row: Int?,
            modifiers: Int,
        ) = Unit
        override fun close() { closeCount++ }
    }

    @Test
    fun unavailableCommandsKeepTheirExistingBehavior() = runTest {
        val bridge = ProcessSessionBridge()

        bridge.disconnect()
        bridge.approveTrust(true)
        bridge.approveHostKeyReset(true)
        bridge.approveInstall(true)
        assertNull(bridge.attachTerminal("session", "pane", 80, 24, false))
        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.runTest { bridge.focusPane("session", "pane") }
        }

        assertEquals("The Herdr connection is not ready.", failure.message)
        assertEquals(SessionPublication(), bridge.snapshot.value)
    }

    @Test
    fun registeredEndpointWithoutActiveBridgePreservesExactHierarchyFailure() {
        val bridge = ProcessSessionBridge()
        val hierarchy = HerdrHierarchyCommands { null }
        bridge.register(object : Endpoint() {
            override suspend fun focusPane(sessionId: String, paneId: String): ActionOutcome =
                hierarchy.focusPane(sessionId, paneId)
        })

        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.runTest { bridge.focusPane("session", "pane") }
        }

        assertEquals("The Herdr connection is not ready.", failure.message)
    }

    @Test
    fun matchingUnregisterClearsPublishedSessionAtomically() {
        val bridge = ProcessSessionBridge()
        val endpoint = Endpoint()
        val registration = bridge.register(endpoint)
        val hierarchy = mapOf("work" to SessionState(epoch = "epoch"))
        registration.publish(
            SessionPublication(
                connection = ConnectionState.Connected(7L, hierarchy),
                diagnostics = listOf(ConnectionDiagnostic(1L, "diagnostic")),
                ownership = ConnectionOwnershipMode.Foreground,
            ),
        )

        registration.close()

        assertEquals(SessionPublication(), bridge.snapshot.value)
    }

    @Test
    fun registeringSameLiveEndpointReusesPublicationAuthority() {
        val bridge = ProcessSessionBridge()
        val endpoint = Endpoint()

        val first = bridge.register(endpoint)
        val rebound = bridge.register(endpoint)

        assertSame(first, rebound)
        assertEquals(first.epoch, rebound.epoch)
    }

    private open class Endpoint : ServiceEndpoint {
        lateinit var registration: ServiceRegistration
        var disconnectCalls = 0
        var focusCalls = 0
        var attachCalls = 0

        override fun registered(registration: ServiceRegistration) {
            this.registration = registration
        }

        override fun disconnect() { disconnectCalls++ }
        override fun approveTrust(accept: Boolean) = Unit
        override fun approveHostKeyReset(accept: Boolean) = Unit
        override fun approveInstall(accept: Boolean) = Unit
        override suspend fun attachTerminal(
            sessionId: String,
            paneId: String,
            cols: Int,
            rows: Int,
            takeover: Boolean,
        ): TerminalLease? {
            attachCalls++
            return null
        }

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
        override suspend fun focusPane(sessionId: String, paneId: String): ActionOutcome {
            focusCalls++
            return ActionOutcome.Succeeded
        }

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

    private companion object {
        fun connected(routeId: Long) = SessionPublication(
            connection = ConnectionState.Connected(routeId, emptyMap()),
        )
    }
}
