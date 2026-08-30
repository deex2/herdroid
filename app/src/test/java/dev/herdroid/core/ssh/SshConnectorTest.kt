package dev.herdroid.core.ssh

import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.ssh.keys.HardwareKeyMaterial
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.InvalidKeyException
import java.security.PublicKey
import java.security.Signature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectorTest {
    @Test
    fun `production Android config resets a pinned provider and resolves P256 signing`() {
        SecurityUtils.setSecurityProvider("BC")

        productionSshClient().close()
        val algorithm = productionSshConfig().keyAlgorithms
            .single { it.name == KeyType.ECDSA256.toString() }
            .create()

        assertNull(SecurityUtils.getSecurityProvider())
        assertEquals(KeyType.ECDSA256, algorithm.keyFormat)
        val pair = p256KeyPair()
        val challenge = "production-sshj-p256".encodeToByteArray()
        val signer = algorithm.newSignature()
        signer.initSign(pair.private)
        signer.update(challenge)
        val derSignature = signer.sign()
        assertTrue(signer.encode(derSignature).isNotEmpty())
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(challenge)
            verify(derSignature)
        })
    }

    @Test
    fun `hardware key authenticates a direct target with the loaded pair`() {
        val client = RecordingClient("target", keyPair.public)
        val pair = p256KeyPair()
        val endpoint = hardwareEndpoint("target-key", pair)

        connector(client, loadHardwareKey = { alias ->
            assertEquals("target-key", alias)
            hardwareMaterial(alias, pair)
        }).connect(
            SshConnectionInput(routeName = "hardware", target = endpoint, jump = null),
            targetKnownHosts = listOf(known(endpoint, keyPair.public)),
        ).close()

        assertSame(pair.private, client.keyProvider!!.private)
        assertSame(pair.public, client.keyProvider!!.public)
        assertEquals(listOf("target.verifier", "target.connect", "target.key", "target.close"), client.events)
    }

    @Test
    fun `hardware key authenticates only the jump when target uses password`() {
        val jumpClient = RecordingClient("jump", keyPair.public)
        val targetClient = RecordingClient("target", keyPair.public)
        val pair = p256KeyPair()
        val jump = hardwareEndpoint("jump-key", pair, hostname = "jump.example", port = 22)
        val target = endpoint()

        connector(jumpClient, targetClient, loadHardwareKey = { hardwareMaterial(it, pair) }).connect(
            SshConnectionInput(routeName = "jump-key", target = target, jump = jump),
            targetKnownHosts = listOf(known(target, keyPair.public)),
            jumpKnownHosts = listOf(known(jump, keyPair.public)),
        ).close()

        assertSame(pair.private, jumpClient.keyProvider!!.private)
        assertNull(targetClient.keyProvider)
        assertTrue("target.password" in targetClient.events)
    }

    @Test
    fun `distinct hardware keys authenticate jump and target with their exact pairs`() {
        val jumpClient = RecordingClient("jump", keyPair.public)
        val targetClient = RecordingClient("target", keyPair.public)
        val jumpPair = p256KeyPair()
        val targetPair = p256KeyPair()
        val jump = hardwareEndpoint("jump-key", jumpPair, hostname = "jump.example", port = 22)
        val target = hardwareEndpoint("target-key", targetPair)

        connector(jumpClient, targetClient, loadHardwareKey = { alias ->
            hardwareMaterial(alias, if (alias == "jump-key") jumpPair else targetPair)
        }).connect(
            SshConnectionInput(routeName = "two-keys", target = target, jump = jump),
            targetKnownHosts = listOf(known(target, keyPair.public)),
            jumpKnownHosts = listOf(known(jump, keyPair.public)),
        ).close()

        assertSame(jumpPair.private, jumpClient.keyProvider!!.private)
        assertSame(targetPair.private, targetClient.keyProvider!!.private)
    }

    @Test
    fun `shared hardware key authenticates both hops with the same pair`() {
        val jumpClient = RecordingClient("jump", keyPair.public)
        val targetClient = RecordingClient("target", keyPair.public)
        val pair = p256KeyPair()
        val jump = hardwareEndpoint("shared-key", pair, hostname = "jump.example", port = 22)
        val target = hardwareEndpoint("shared-key", pair)

        connector(jumpClient, targetClient, loadHardwareKey = { hardwareMaterial(it, pair) }).connect(
            SshConnectionInput(routeName = "shared-key", target = target, jump = jump),
            targetKnownHosts = listOf(known(target, keyPair.public)),
            jumpKnownHosts = listOf(known(jump, keyPair.public)),
        ).close()

        assertSame(pair.private, jumpClient.keyProvider!!.private)
        assertSame(pair.private, targetClient.keyProvider!!.private)
    }

    @Test
    fun `hardware key loader failures are normalized before SSHJ auth`() {
        listOf(
            IllegalStateException("missing alias"),
            InvalidKeyException("invalidated"),
            IllegalArgumentException("not hardware backed"),
        ).forEach { cause ->
            val client = RecordingClient("target", keyPair.public)
            val pair = p256KeyPair()
            val endpoint = hardwareEndpoint("unavailable", pair)

            val failure = assertThrows(HardwareKeyUnavailableException::class.java) {
                connector(client, loadHardwareKey = { throw cause }).connect(
                    SshConnectionInput(routeName = "unavailable", target = endpoint, jump = null),
                    targetKnownHosts = listOf(known(endpoint, keyPair.public)),
                )
            }

            assertEquals("Hardware key unavailable. Select or create a replacement key.", failure.message)
            assertSame(cause, failure.cause)
            assertTrue("target.key" !in client.events)
            assertEquals("target.close", client.events.last())
        }
    }

    @Test
    fun `mismatched hardware public blob is normalized before SSHJ auth`() {
        val client = RecordingClient("target", keyPair.public)
        val pair = p256KeyPair()
        val endpoint = hardwareEndpoint("mismatch", p256KeyPair())

        val failure = assertThrows(HardwareKeyUnavailableException::class.java) {
            connector(client, loadHardwareKey = { hardwareMaterial(it, pair) }).connect(
                SshConnectionInput(routeName = "mismatch", target = endpoint, jump = null),
                targetKnownHosts = listOf(known(endpoint, keyPair.public)),
            )
        }

        assertEquals("Hardware key unavailable. Select or create a replacement key.", failure.message)
        assertTrue("target.key" !in client.events)
        assertEquals("target.close", client.events.last())
    }

    @Test
    fun `mismatched loaded hardware key pair is normalized before SSHJ auth`() {
        val client = RecordingClient("target", keyPair.public)
        val publicPair = p256KeyPair()
        val mismatchedPair = KeyPair(publicPair.public, p256KeyPair().private)
        val endpoint = hardwareEndpoint("mismatched-pair", publicPair)

        val failure = assertThrows(HardwareKeyUnavailableException::class.java) {
            connector(client, loadHardwareKey = {
                HardwareKeyMaterial(it, mismatchedPair, publicBlob(publicPair), HardwareSecurityLevel.TEE)
            }).connect(
                SshConnectionInput(routeName = "mismatched-pair", target = endpoint, jump = null),
                targetKnownHosts = listOf(known(endpoint, keyPair.public)),
            )
        }

        assertEquals("Hardware key unavailable. Select or create a replacement key.", failure.message)
        assertTrue("target.key" !in client.events)
        assertEquals("target.close", client.events.last())
    }

    @Test
    fun `hardware auth signing security failure is normalized`() {
        val pair = p256KeyPair()
        val endpoint = hardwareEndpoint("invalidated-at-auth", pair)
        val signingFailure = UserAuthException(
            "signing failed",
            SSHRuntimeException(InvalidKeyException("invalidated")),
        )
        val client = RecordingClient("target", keyPair.public, keyFailure = signingFailure)

        val failure = assertThrows(HardwareKeyUnavailableException::class.java) {
            connector(client, loadHardwareKey = { hardwareMaterial(it, pair) }).connect(
                SshConnectionInput(routeName = "invalidated-at-auth", target = endpoint, jump = null),
                targetKnownHosts = listOf(known(endpoint, keyPair.public)),
            )
        }

        assertEquals("Hardware key unavailable. Select or create a replacement key.", failure.message)
        assertSame(signingFailure, failure.cause)
        assertEquals("target.close", client.events.last())
    }

    @Test
    fun `ordinary remote hardware key rejection is normalized at the module boundary`() {
        val pair = p256KeyPair()
        val endpoint = hardwareEndpoint("remote-rejection", pair)
        val rejection = UserAuthException("server rejected key")
        val client = RecordingClient("target", keyPair.public, keyFailure = rejection)

        val failure = assertThrows(SshAuthenticationFailedException::class.java) {
            connector(client, loadHardwareKey = { hardwareMaterial(it, pair) }).connect(
                SshConnectionInput(routeName = "remote-rejection", target = endpoint, jump = null),
                targetKnownHosts = listOf(known(endpoint, keyPair.public)),
            )
        }

        assertSame(rejection, failure.cause)
        assertEquals("target.close", client.events.last())
    }

    @Test
    fun `direct connection installs rejecting verifier before connect and authenticates password`() {
        val client = RecordingClient("target", keyPair.public)
        val passwordBytes = "sëcret".encodeToByteArray()
        val endpoint = endpoint(
            authentication = SshAuthenticationInput.Password(passwordBytes),
        )

        val connected = connector(client).connect(
            SshConnectionInput(routeName = "direct", target = endpoint, jump = null),
            targetKnownHosts = listOf(known(endpoint, keyPair.public)),
        )

        assertSame(client, connected.target)
        assertNull(connected.jump)
        assertEquals(listOf("target.verifier", "target.connect", "target.password"), client.events)
        assertEquals("sëcret", client.passwordCopy!!.concatToString())
        assertArrayEquals(ByteArray(passwordBytes.size), passwordBytes)
        assertArrayEquals(CharArray(client.passwordReference!!.size), client.passwordReference)
        assertEquals(SshConnector.CONNECT_TIMEOUT_MILLIS, client.connectTimeout)
        assertEquals(SshConnector.AUTH_TIMEOUT_MILLIS, client.timeoutAtAuthentication)
        assertEquals(0, client.timeout)
        assertEquals(0, client.socket.soTimeout)
        assertEquals(SshConnector.AUTH_TIMEOUT_MILLIS, client.transport.timeoutMs)
        assertEquals(SshConnector.AUTH_TIMEOUT_MILLIS, client.connection.timeoutMs)
        assertEquals(30, client.keepAliveSeconds)
        connected.close()
    }

    @Test
    fun `connector clears owned password input on failure and cancellation`() {
        val failureBytes = "failure".encodeToByteArray()
        val failureEndpoint = endpoint(authentication = SshAuthenticationInput.Password(failureBytes))
        assertThrows(HostKeyApprovalRequired::class.java) {
            connector(RecordingClient("target", keyPair.public)).connect(
                SshConnectionInput("failure", failureEndpoint, null),
                emptyList(),
            )
        }

        val cancelledBytes = "cancelled".encodeToByteArray()
        val cancelled = SshConnectionInput(
            "cancelled",
            endpoint(authentication = SshAuthenticationInput.Password(cancelledBytes)),
            null,
        )
        assertThrows(CancellationException::class.java) {
            SshConnector(
                keepAliveSetter = { _, _ -> },
                clientFactory = { throw CancellationException("cancelled") },
                loadHardwareKey = { error("unexpected hardware key") },
                ioDispatcher = Dispatchers.Unconfined,
            ).connect(cancelled, emptyList())
        }

        assertArrayEquals(ByteArray(failureBytes.size), failureBytes)
        assertArrayEquals(ByteArray(cancelledBytes.size), cancelledBytes)
    }

    @Test
    fun `unknown host key closes client and surfaces explicit approval data`() {
        val client = RecordingClient("target", keyPair.public)
        val endpoint = endpoint(hostname = "new.example")

        val failure = assertThrows(HostKeyApprovalRequired::class.java) {
            connector(client).connect(
                SshConnectionInput(routeName = "unknown", target = endpoint, jump = null),
                targetKnownHosts = emptyList(),
            )
        }

        assertSame(failure.approval.candidate, failure.candidate)
        assertEquals(Hop.TARGET, failure.candidate.hop)
        assertEquals("new.example", failure.candidate.hostname)
        assertEquals(2222, failure.candidate.port)
        assertEquals("ssh-ed25519", failure.candidate.algorithm)
        assertTrue(failure.candidate.sha256.startsWith("SHA256:"))
        assertEquals(
            known(endpoint, keyPair.public).keyBase64,
            failure.candidate.keyBase64,
        )
        assertEquals(listOf("target.verifier", "target.connect", "target.close"), client.events)
    }

    @Test
    fun `changed host key is a hard failure with no approval result`() {
        val client = RecordingClient("target", keyPair.public)
        val endpoint = endpoint(hostname = "changed.example")
        val changedKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public

        val failure = assertThrows(HostKeyChangedException::class.java) {
            connector(client).connect(
                SshConnectionInput(routeName = "changed", target = endpoint, jump = null),
                targetKnownHosts = listOf(known(endpoint, changedKey)),
            )
        }

        assertEquals("changed.example", failure.rejection.actual.hostname)
        assertEquals(2222, failure.rejection.actual.port)
        assertTrue(failure.rejection.expected.sha256.startsWith("SHA256:"))
        assertTrue(failure.rejection.actual.sha256.startsWith("SHA256:"))
        assertTrue(failure.rejection.expected.keyBase64 != failure.rejection.actual.keyBase64)
        assertEquals(listOf("target.verifier", "target.connect", "target.close"), client.events)
    }

    @Test
    fun `one jump uses one direct channel and closes target direct jump once in reverse`() {
        val allEvents = mutableListOf<String>()
        val jumpClient = RecordingClient("jump", keyPair.public, allEvents)
        val targetClient = RecordingClient("target", keyPair.public, allEvents)
        val jump = endpoint(hostname = "jump.example", port = 22)
        val target = endpoint(hostname = "target.example", port = 2222)

        val connected = connector(jumpClient, targetClient).connect(
            SshConnectionInput(routeName = "jumped", target = target, jump = jump),
            targetKnownHosts = listOf(known(target, keyPair.public)),
            jumpKnownHosts = listOf(known(jump, keyPair.public)),
        )

        assertSame(targetClient, connected.target)
        assertSame(jumpClient, connected.jump)
        assertEquals(1, jumpClient.directConnections)
        assertEquals("target.example", jumpClient.direct!!.remoteHost)
        assertEquals(2222, jumpClient.direct!!.remotePort)
        assertTrue(targetClient.events.contains("target.connectVia"))

        connected.close()
        connected.close()

        assertEquals(
            listOf("target.close", "direct.close", "jump.close"),
            allEvents.takeLast(3),
        )
        assertEquals(1, allEvents.count { it == "target.close" })
        assertEquals(1, allEvents.count { it == "direct.close" })
        assertEquals(1, allEvents.count { it == "jump.close" })
    }

    @Test
    fun `unknown target behind approved jump reports target and unwinds target direct jump`() {
        val allEvents = mutableListOf<String>()
        val jumpClient = RecordingClient("jump", keyPair.public, allEvents)
        val targetClient = RecordingClient("target", keyPair.public, allEvents)
        val jump = endpoint(hostname = "jump.example", port = 22)
        val target = endpoint(hostname = "new-target.example", port = 2222)

        val failure = assertThrows(HostKeyApprovalRequired::class.java) {
            connector(jumpClient, targetClient).connect(
                SshConnectionInput(routeName = "unknown-target", target = target, jump = jump),
                targetKnownHosts = emptyList(),
                jumpKnownHosts = listOf(known(jump, keyPair.public)),
            )
        }

        assertEquals(Hop.TARGET, failure.approval.candidate.hop)
        assertEquals(
            listOf("target.close", "direct.close", "jump.close"),
            allEvents.takeLast(3),
        )
    }

    private fun endpoint(
        hostname: String = "target.example",
        port: Int = 2222,
        authentication: SshAuthenticationInput = SshAuthenticationInput.Password("password".encodeToByteArray()),
    ) = SshEndpointInput(hostname, port, "alice", authentication, null)

    private fun hardwareEndpoint(
        alias: String,
        pair: KeyPair,
        hostname: String = "target.example",
        port: Int = 2222,
    ) = endpoint(
        hostname,
        port,
        SshAuthenticationInput.HardwareKey(1, alias, publicBlob(pair)),
    )

    private fun hardwareMaterial(alias: String, pair: KeyPair) = HardwareKeyMaterial(
        alias,
        pair,
        publicBlob(pair),
        HardwareSecurityLevel.TEE,
    )

    private fun publicBlob(pair: KeyPair) = Buffer.PlainBuffer().putPublicKey(pair.public).compactData

    private fun p256KeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(256)
        generateKeyPair()
    }

    private fun connector(
        vararg clients: RecordingClient,
        loadHardwareKey: (String) -> HardwareKeyMaterial = { error("unexpected hardware key") },
    ): SshConnector {
        val remaining = ArrayDeque(clients.toList())
        return SshConnector(
            keepAliveSetter = { client, seconds ->
                (client as RecordingClient).keepAliveSeconds = seconds
            },
            clientFactory = { remaining.removeFirst() },
            loadHardwareKey = loadHardwareKey,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun known(endpoint: SshEndpointInput, key: PublicKey) = KnownHostRecord(
        hostname = endpoint.hostname,
        port = endpoint.port,
        algorithm = KeyType.fromKey(key).toString(),
        keyBase64 = java.util.Base64.getEncoder().encodeToString(
            Buffer.PlainBuffer().putPublicKey(key).compactData,
        ),
        acceptedAtEpochMillis = 1L,
    )

    private class RecordingClient(
        private val label: String,
        private val presentedKey: PublicKey,
        private val sharedEvents: MutableList<String>? = null,
        private val keyFailure: Throwable? = null,
    ) : SSHClient() {
        val events = mutableListOf<String>()
        var passwordCopy: CharArray? = null
        var passwordReference: CharArray? = null
        var keyProvider: KeyProvider? = null
        var keepAliveSeconds: Int? = null
        var timeoutAtAuthentication: Int? = null
        private val recordingSocket = java.net.Socket()
        var directConnections = 0
        var direct: RecordingDirectConnection? = null
        private var verifier: HostKeyVerifier? = null

        override fun addHostKeyVerifier(verifier: HostKeyVerifier) {
            event("$label.verifier")
            this.verifier = verifier
        }

        override fun connect(hostname: String, port: Int) {
            event("$label.connect")
            check(keepAliveSeconds == 30) { "keepalive must be configured before connect" }
            recordingSocket.soTimeout = timeout
            verify(hostname, port)
        }

        override fun connectVia(directConnection: DirectConnection) {
            event("$label.connectVia")
            check(keepAliveSeconds == 30) { "keepalive must be configured before connectVia" }
            verify(directConnection.remoteHost, directConnection.remotePort)
        }

        override fun authPassword(username: String, password: CharArray) {
            event("$label.password")
            timeoutAtAuthentication = timeout
            passwordReference = password
            passwordCopy = password.copyOf()
        }

        override fun loadKeys(keyPair: KeyPair): KeyProvider = KeyPairWrapper(keyPair)

        override fun authPublickey(username: String, vararg keyProviders: KeyProvider) {
            event("$label.key")
            timeoutAtAuthentication = timeout
            keyProvider = keyProviders.single()
            keyFailure?.let { throw it }
        }

        override fun getSocket(): java.net.Socket = recordingSocket

        override fun newDirectConnection(host: String, port: Int): DirectConnection {
            directConnections++
            event("$label.direct")
            return RecordingDirectConnection(connection, host, port, sharedEvents ?: events).also {
                direct = it
            }
        }

        override fun close() {
            event("$label.close")
            super.close()
            transport.disconnect()
        }

        private fun verify(hostname: String, port: Int) {
            val configured = verifier ?: throw AssertionError("connect called before verifier")
            if (!configured.verify(hostname, port, presentedKey)) {
                throw IOException("host key rejected")
            }
        }

        private fun event(value: String) {
            events += value
            sharedEvents?.add(value)
        }
    }

    private class RecordingDirectConnection(
        connection: net.schmizz.sshj.connection.Connection,
        host: String,
        port: Int,
        private val events: MutableList<String>,
    ) : DirectConnection(connection, host, port) {
        override fun close() {
            events += "direct.close"
        }
    }

    private companion object {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }
}
