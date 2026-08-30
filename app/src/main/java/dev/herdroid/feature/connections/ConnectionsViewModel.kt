package dev.herdroid.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.herdroid.core.data.EditableEndpoint
import dev.herdroid.core.data.EditableRoute
import dev.herdroid.core.data.EndpointAuthenticationInput
import dev.herdroid.core.data.EndpointWriteInput
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.data.LocalDataUnavailableException
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteWriteInput
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.SavedRouteSummary
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionOwnershipMode
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.api.ConnectionState
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EndpointDraft(
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val keyId: Long? = null,
    val herdrPath: String = "",
) {
    override fun toString() =
        "EndpointDraft(hostname=$hostname, port=$port, username=$username, credentials=redacted, herdrPath=$herdrPath)"

    fun withKey(id: Long) = copy(password = "", keyId = id)

    fun toEndpoint(target: Boolean, keys: List<HardwareKeyMetadata>): EndpointWriteInput {
        require(keyId == null || password.isEmpty()) { "Choose either a password or a hardware key" }
        val normalizedHostname = hostname.trim()
        require(normalizedHostname.isNotEmpty()) { "hostname must not be blank" }
        val normalizedPort = port.toIntOrNull() ?: 0
        require(normalizedPort in 1..65535) { "port must be between 1 and 65535" }
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotEmpty()) { "username must not be blank" }
        val path = herdrPath.trim().takeIf { target && it.isNotEmpty() }
        require(path == null || path.startsWith('/') || path.startsWith("\\\\") || Regex("[A-Za-z]:[\\\\/].*").matches(path)) {
            "Herdr path must be absolute"
        }
        val authentication = keyId?.let { id ->
            requireNotNull(keys.singleOrNull { it.id == id }) { "Selected hardware key is unavailable" }
            EndpointAuthenticationInput.HardwareKey(id)
        } ?: EndpointAuthenticationInput.Password(password.encodeToByteArray().also {
            require(it.isNotEmpty()) { "Password is required" }
        })
        return try {
            EndpointWriteInput(normalizedHostname, normalizedPort, normalizedUsername, authentication, path)
        } catch (failure: Throwable) {
            authentication.close()
            throw failure
        }
    }
}

data class RouteDraft(
    val id: Long = 0,
    val name: String = "",
    val target: EndpointDraft = EndpointDraft(),
    val jump: EndpointDraft? = null,
) {
    override fun toString() = "RouteDraft(id=$id, name=$name, endpoints=redacted)"

    fun toRouteWriteInput(keys: List<HardwareKeyMetadata> = emptyList()): RouteWriteInput {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "name must not be blank" }
        val targetInput = target.toEndpoint(target = true, keys)
        var jumpInput: EndpointWriteInput? = null
        return try {
            jumpInput = jump?.toEndpoint(target = false, keys)
            RouteWriteInput(id, normalizedName, targetInput, jumpInput)
        } catch (failure: Throwable) {
            jumpInput?.close()
            targetInput.close()
            throw failure
        }
    }

    companion object {
        fun from(route: EditableRoute) = RouteDraft(
            id = route.id,
            name = route.name,
            target = route.target.toDraft(target = true),
            jump = route.jump?.toDraft(target = false),
        )
    }
}

private fun EditableEndpoint.toDraft(target: Boolean) = EndpointDraft(
    hostname = hostname,
    port = port.toString(),
    username = username,
    keyId = keyId,
    herdrPath = herdrPath.orEmpty().takeIf { target }.orEmpty(),
)

data class ConnectionsUiState(
    val availability: LocalDataAvailability = LocalDataAvailability.Initializing,
    val routes: List<SavedRouteSummary> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val activityBound: Boolean = true,
    val diagnostics: List<ConnectionDiagnostic> = emptyList(),
    val showDiagnostics: Boolean = false,
    val openMessage: String? = null,
)

private data class ConnectionsOverlay(
    val showDiagnostics: Boolean = false,
    val openMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val routes: RouteRepository,
    private val session: ConnectionSession,
    availability: StateFlow<@JvmSuppressWildcards LocalDataAvailability>,
) : ViewModel() {
    private val overlay = MutableStateFlow(ConnectionsOverlay())

    val uiState: StateFlow<ConnectionsUiState> = combine(
        routes.routes
            .onStart { emit(emptyList()) }
            .catch { failure ->
                if (failure is LocalDataUnavailableException) emit(emptyList()) else throw failure
            },
        session.state,
        session.ownership,
        session.diagnostics,
        availability,
    ) { savedRoutes, connection, ownership, diagnostics, localData ->
        ConnectionsUiState(
            availability = localData,
            routes = savedRoutes,
            connectionState = connection,
            activityBound = ownership == ConnectionOwnershipMode.ActivityBound,
            diagnostics = diagnostics,
        )
    }.combine(overlay) { state, transient ->
        state.copy(
            showDiagnostics = transient.showDiagnostics,
            openMessage = transient.openMessage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConnectionsUiState(
            availability = availability.value,
            connectionState = session.state.value,
            activityBound = session.ownership.value == ConnectionOwnershipMode.ActivityBound,
            diagnostics = session.diagnostics.value,
        ),
    )

    fun connect(routeId: Long) = session.connect(routeId)
    fun disconnect() = session.disconnect()
    fun delete(routeId: Long) = viewModelScope.launch {
        if ((session.state.value as? ConnectionState.Connected)?.routeId == routeId) session.disconnect()
        try {
            routes.delete(routeId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            showOpenMessage("Unable to delete connection: ${failure.message ?: "unknown error"}")
        }
    }
    fun approveTrust(accept: Boolean) = session.approveTrust(accept)
    fun approveHostKeyReset(accept: Boolean) = session.approveHostKeyReset(accept)
    fun approveBridgeInstall(accept: Boolean) = session.approveInstall(accept)

    fun showDiagnostics() = overlay.update { it.copy(showDiagnostics = true) }
    fun dismissDiagnostics() = overlay.update { it.copy(showDiagnostics = false) }
    fun showOpenMessage(message: String) = overlay.update { it.copy(openMessage = message) }
    fun clearOpenMessage() = overlay.update { it.copy(openMessage = null) }
}
