package dev.herdroid.core.data

import dev.herdroid.core.model.SavedRouteSummary
import kotlinx.coroutines.flow.Flow

class RouteWriteInput(
    val id: Long,
    val name: String,
    val target: EndpointWriteInput,
    val jump: EndpointWriteInput?,
) : AutoCloseable {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }

    override fun close() {
        target.close()
        jump?.close()
    }
}

sealed interface EndpointAuthenticationInput : AutoCloseable {
    class Password(private val ownedBytes: ByteArray) : EndpointAuthenticationInput {
        fun copyForTransaction(): ByteArray = ownedBytes.copyOf()
        override fun close() = ownedBytes.fill(0)
        override fun toString() = "Password(redacted)"
    }

    data class HardwareKey(val keyId: Long) : EndpointAuthenticationInput {
        override fun close() = Unit
    }
}

class EndpointWriteInput(
    val hostname: String,
    val port: Int,
    val username: String,
    val authentication: EndpointAuthenticationInput,
    val herdrPath: String?,
) : AutoCloseable {
    init {
        require(hostname.isNotBlank()) { "hostname must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }
        require(username.isNotBlank()) { "username must not be blank" }
    }

    override fun close() = authentication.close()
}

data class EditableRoute(
    val id: Long,
    val name: String,
    val target: EditableEndpoint,
    val jump: EditableEndpoint?,
)

data class EditableEndpoint(
    val hostname: String,
    val port: Int,
    val username: String,
    val keyId: Long?,
    val herdrPath: String?,
)

interface RouteRepository {
    val routes: Flow<List<SavedRouteSummary>>
    suspend fun findEditable(routeId: Long): EditableRoute?
    suspend fun save(input: RouteWriteInput): Long
    suspend fun delete(routeId: Long)
}
