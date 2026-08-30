package dev.herdroid.core.data

import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.KnownHostRecord

class ConnectionRouteInput(
    val routeId: Long,
    val routeName: String,
    val target: ConnectionEndpointInput,
    val jump: ConnectionEndpointInput?,
) : AutoCloseable {
    override fun close() {
        target.close()
        jump?.close()
    }
}

class ConnectionEndpointInput(
    val hostname: String,
    val port: Int,
    val username: String,
    val authentication: ConnectionAuthenticationInput,
    val herdrPath: String?,
    val bridgeCache: BridgeLaunchCache? = null,
) : AutoCloseable {
    override fun close() = authentication.close()
}

data class BridgeLaunchCache(
    val target: String,
    val herdrPath: String,
    val bridgePath: String,
)

sealed interface ConnectionAuthenticationInput : AutoCloseable {
    class Password(private val ownedBytes: ByteArray) : ConnectionAuthenticationInput {
        fun moveToConnector(): ByteArray = ownedBytes.copyOf()
        override fun close() = ownedBytes.fill(0)
        override fun toString() = "Password(redacted)"
    }

    class HardwareKey(
        val keyId: Long,
        val alias: String,
        publicKeyOpenSsh: ByteArray,
    ) : ConnectionAuthenticationInput {
        private val ownedPublicKey = publicKeyOpenSsh.copyOf()
        fun copyPublicKeyForConnection(): ByteArray = ownedPublicKey.copyOf()
        override fun close() = ownedPublicKey.fill(0)
    }
}

interface ConnectionRouteRepository {
    suspend fun loadForConnection(routeId: Long): ConnectionRouteInput
    suspend fun knownHosts(hostname: String, port: Int): List<KnownHostRecord>
    suspend fun updateKnownHost(record: KnownHostRecord)
    suspend fun deleteKnownHost(candidate: HostKeyCandidate)
    suspend fun updateBridgeCache(routeId: Long, cache: BridgeLaunchCache?)
}

class RouteNotFoundException(val routeId: Long) : NoSuchElementException("Unknown route id: $routeId")
