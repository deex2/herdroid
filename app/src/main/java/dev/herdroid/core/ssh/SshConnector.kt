package dev.herdroid.core.ssh

import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.ssh.keys.HardwareKeyMaterial
import dev.herdroid.core.ssh.keys.HardwareSshKeyStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.ProviderException
import java.security.Signature
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper

class HardwareKeyUnavailableException(cause: Throwable? = null) :
    IOException("Hardware key unavailable. Select or create a replacement key.", cause)

class SshConnector internal constructor(
    private val keepAliveSetter: (SSHClient, Int) -> Unit,
    private val clientFactory: () -> SSHClient,
    private val loadHardwareKey: (String) -> HardwareKeyMaterial,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    internal constructor(
        hardwareKeys: HardwareSshKeyStore,
        @Dispatcher(HerdroidDispatchers.IO) ioDispatcher: CoroutineDispatcher,
    ) : this(
        keepAliveSetter = { client, seconds -> client.connection.keepAlive.keepAliveInterval = seconds },
        clientFactory = ::productionSshClient,
        loadHardwareKey = hardwareKeys::load,
        ioDispatcher = ioDispatcher,
    )

    fun connect(
        input: SshConnectionInput,
        targetKnownHosts: List<KnownHostRecord>,
        jumpKnownHosts: List<KnownHostRecord> = emptyList(),
    ): ConnectedRoute {
        var jumpClient: SSHClient? = null
        var direct: DirectConnection? = null
        var targetClient: SSHClient? = null
        try {
            if (input.jump != null) {
                jumpClient = connectAndAuthenticate(input.jump, Hop.JUMP, jumpKnownHosts, null)
                direct = jumpClient.newDirectConnection(input.target.hostname, input.target.port)
            }
            targetClient = connectAndAuthenticate(input.target, Hop.TARGET, targetKnownHosts, direct)
            return ConnectedRoute(targetClient, jumpClient, direct, ioDispatcher)
        } catch (failure: Throwable) {
            ignoreCloseFailure { targetClient?.close() }
            ignoreCloseFailure { direct?.close() }
            ignoreCloseFailure { jumpClient?.close() }
            throw failure
        } finally {
            input.close()
        }
    }

    private fun connectAndAuthenticate(
        endpoint: SshEndpointInput,
        hop: Hop,
        knownHosts: List<KnownHostRecord>,
        direct: DirectConnection?,
    ): SSHClient {
        val client = clientFactory()
        val verifier = RejectingHostKeyVerifier(hop, knownHosts)
        try {
            configure(client)
            client.addHostKeyVerifier(verifier)
            if (direct == null) {
                client.connect(endpoint.hostname, endpoint.port)
            } else {
                client.connectVia(direct)
            }
            authenticate(client, endpoint)
            client.socket?.soTimeout = 0
            client.timeout = 0
            return client
        } catch (failure: Throwable) {
            ignoreCloseFailure(client::close)
            throw normalizeAuthenticationFailure(verifier.connectionFailure(failure))
        }
    }

    private fun configure(client: SSHClient) {
        client.connectTimeout = CONNECT_TIMEOUT_MILLIS
        client.timeout = AUTH_TIMEOUT_MILLIS
        client.transport.timeoutMs = AUTH_TIMEOUT_MILLIS
        client.connection.timeoutMs = AUTH_TIMEOUT_MILLIS
        keepAliveSetter(client, KEEPALIVE_SECONDS)
    }

    private fun authenticate(client: SSHClient, endpoint: SshEndpointInput) {
        when (val authentication = endpoint.authentication) {
            is SshAuthenticationInput.Password -> {
                val encoded = authentication.copyForAuthentication()
                try {
                    authenticatePassword(client, endpoint.username, encoded)
                } finally {
                    encoded.fill(0)
                }
            }
            is SshAuthenticationInput.HardwareKey -> {
                val provider = KeyPairWrapper(verifiedHardwareKeyPair(authentication))
                try {
                    client.authPublickey(endpoint.username, provider)
                } catch (failure: Exception) {
                    if (generateSequence(failure as Throwable?) { it.cause }.any {
                            it is GeneralSecurityException || it is ProviderException || it is SecurityException
                        }
                    ) {
                        throw HardwareKeyUnavailableException(failure)
                    }
                    throw failure
                }
            }
        }
    }

    private fun verifiedHardwareKeyPair(authentication: SshAuthenticationInput.HardwareKey): KeyPair {
        val expectedPublicKey = authentication.publicKeyOpenSsh()
        try {
            return loadHardwareKey(authentication.alias).use { material ->
                require(material.alias == authentication.alias)
                when (material.securityLevel) {
                    HardwareSecurityLevel.TEE, HardwareSecurityLevel.STRONGBOX -> Unit
                }
                val loadedPublicKey = material.publicKeyOpenSsh()
                try {
                    require(MessageDigest.isEqual(loadedPublicKey, expectedPublicKey))
                } finally {
                    loadedPublicKey.fill(0)
                }
                val challenge = "herdroid-hardware-key-proof-v1".encodeToByteArray()
                var signature: ByteArray? = null
                try {
                    signature = Signature.getInstance("SHA256withECDSA").run {
                        initSign(material.keyPair.private)
                        update(challenge)
                        sign()
                    }
                    require(Signature.getInstance("SHA256withECDSA").run {
                        initVerify(material.keyPair.public)
                        update(challenge)
                        verify(signature)
                    })
                } finally {
                    signature?.fill(0)
                    challenge.fill(0)
                }
                material.keyPair
            }
        } catch (failure: HardwareKeyUnavailableException) {
            throw failure
        } catch (failure: Exception) {
            throw HardwareKeyUnavailableException(failure)
        } finally {
            expectedPublicKey.fill(0)
        }
    }

    private fun authenticatePassword(client: SSHClient, username: String, encoded: ByteArray) {
        val scratch = CharArray(encoded.size)
        val output = CharBuffer.wrap(scratch)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val password: CharArray
        try {
            decoder.decode(ByteBuffer.wrap(encoded), output, true).also {
                if (it.isError) it.throwException()
            }
            decoder.flush(output).also {
                if (it.isError) it.throwException()
            }
            password = scratch.copyOf(output.position())
        } finally {
            scratch.fill('\u0000')
        }
        try {
            client.authPassword(username, password)
        } finally {
            password.fill('\u0000')
        }
    }

    private class RejectingHostKeyVerifier(
        private val hop: Hop,
        private val knownHosts: List<KnownHostRecord>,
    ) : HostKeyVerifier {
        @Volatile
        private var rejection: dev.herdroid.core.model.HostKeyDecision? = null

        override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
            val decision = HostKeyPolicy.decide(hop, hostname, port, key, knownHosts)
            rejection = decision.takeUnless { it == dev.herdroid.core.model.HostKeyDecision.Accept }
            return decision == dev.herdroid.core.model.HostKeyDecision.Accept
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            knownHosts.asSequence()
                .filter { it.hostname == hostname && it.port == port }
                .map { it.algorithm }
                .distinct()
                .toList()

        fun connectionFailure(cause: Throwable): Throwable = when (val decision = rejection) {
            is dev.herdroid.core.model.HostKeyDecision.Ask -> HostKeyApprovalRequired(decision, cause)
            is dev.herdroid.core.model.HostKeyDecision.RejectChanged -> HostKeyChangedException(decision, cause)
            else -> cause
        }
    }

    private fun normalizeAuthenticationFailure(failure: Throwable): Throwable {
        if (failure is HardwareKeyUnavailableException) return failure
        val authentication = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }
            .filterIsInstance<UserAuthException>()
            .firstOrNull()
        return authentication?.let(::SshAuthenticationFailedException) ?: failure
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val AUTH_TIMEOUT_MILLIS = 15_000
        const val COMMAND_TIMEOUT_MILLIS = 30_000L
        private const val KEEPALIVE_SECONDS = 30
    }
}
