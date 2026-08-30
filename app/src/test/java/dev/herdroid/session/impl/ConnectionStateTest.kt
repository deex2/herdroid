package dev.herdroid.session.impl

import dev.herdroid.session.api.*

import dev.herdroid.core.model.SessionState
import dev.herdroid.core.herdr.BridgeProtocolException
import dev.herdroid.core.herdr.BridgeTransportException
import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.ssh.SshAuthenticationFailedException
import dev.herdroid.core.model.RemoteOperatingSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class ConnectionStateTest {
    @Test
    fun `explicit connect progresses and explicit disconnect clears ownership`() {
        val machine = ConnectionStateMachine()

        machine.connect(7)
        assertEquals(ConnectionState.Connecting(7, ConnectStage.LoadingRoute), machine.state.value)
        machine.connected(mapOf("work" to SessionState("epoch-1")))
        assertEquals(ConnectionState.Connected(7, mapOf("work" to SessionState("epoch-1"))), machine.state.value)

        machine.disconnect()
        assertEquals(ConnectionState.Disconnected, machine.state.value)
        assertFalse(machine.networkAvailable())
    }

    @Test
    fun `approval prompts replace the current state`() {
        val machine = ConnectionStateMachine()
        val prompt = HostTrustPrompt(9, HostKeyCandidate(Hop.TARGET, "host", 22, "ssh-ed25519", "sha", "key"))
        machine.needsTrust(prompt)
        assertEquals(ConnectionState.NeedsTrust(prompt), machine.state.value)

        val preview = BridgeApproval("route", RemoteOperatingSystem.LINUX, "x86_64", "target", "/root", "0.1.0", "0.8.0", "a".repeat(64))
        machine.needsBridgeApproval(preview)
        assertEquals(ConnectionState.NeedsBridgeApproval(preview), machine.state.value)
    }

    @Test
    fun `network loss uses capped reconnect with one immediate callback`() {
        val machine = ConnectionStateMachine()
        machine.connect(4)

        machine.transportLost()
        assertEquals(ConnectionState.Reconnecting(4, 1), machine.state.value)
        assertEquals(1L, machine.retryDelaySeconds())
        assertTrue(machine.networkAvailable())
        assertFalse(machine.networkAvailable())
        machine.transportLost()
        assertEquals(2L, machine.retryDelaySeconds())
        repeat(8) { machine.transportLost() }
        assertEquals(30L, machine.retryDelaySeconds())

        machine.shutdown()
        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `authentication is terminal while transport and bridge EOF retry`() {
        assertFalse(transientConnectionFailure(SshAuthenticationFailedException(IOException("bad password"))))
        assertTrue(transientConnectionFailure(IOException("network lost")))
        assertTrue(transientConnectionFailure(BridgeTransportException("Bridge transport failed", IOException("reset"))))
        assertFalse(transientConnectionFailure(BridgeProtocolException("Bridge stdout closed")))
        assertFalse(transientConnectionFailure(BridgeProtocolException("Bridge line exceeds limit")))
    }

    @Test
    fun `diagnostics retain bounded transition history and redact exception secrets`() {
        val clock = AtomicLong(1_000)
        val machine = ConnectionStateMachine(nowMillis = clock::getAndIncrement)

        machine.connect(7)
        machine.connecting(ConnectStage.ConnectingSsh)
        val reason = connectionFailureDiagnostic(
            IOException(
                "Connection reset password=\"two words\" api_key=fixture-key Authorization: Bearer fixture-bearer ssh://user:fixture-pass@host",
                IllegalStateException("token=fixture-token secret=fixture-secret"),
            ),
        )
        machine.transportLost(reason)
        repeat(110) { machine.diagnostic("event-$it") }

        assertTrue(reason.contains("IOException: Connection reset"))
        assertTrue(reason.contains("IllegalStateException"))
        assertFalse(reason.contains("two words"))
        assertFalse(reason.contains("fixture-key"))
        assertFalse(reason.contains("fixture-bearer"))
        assertFalse(reason.contains("fixture-pass"))
        assertFalse(reason.contains("fixture-token"))
        assertFalse(reason.contains("fixture-secret"))
        assertEquals(100, machine.diagnostics.value.size)
        assertEquals("event-10", machine.diagnostics.value.first().message)
        assertEquals("event-109", machine.diagnostics.value.last().message)
    }

    @Test
    fun `concurrent diagnostics do not overwrite each other`() {
        val machine = ConnectionStateMachine()
        val workers = 64
        val barrier = CyclicBarrier(workers)
        val threads = (0 until workers).map { index ->
            thread(start = true) {
                barrier.await()
                machine.diagnostic("event-$index")
            }
        }
        threads.forEach(Thread::join)

        assertEquals(workers, machine.diagnostics.value.map { it.message }.toSet().size)
    }
}
