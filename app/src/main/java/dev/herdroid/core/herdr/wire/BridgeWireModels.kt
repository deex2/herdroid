package dev.herdroid.core.herdr.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class BridgeSessionDescriptor(
    val name: String,
    val running: Boolean,
    @SerialName("socket_path") val socketPath: String,
)

@Serializable
internal sealed interface ServerMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val epoch: String,
        @SerialName("bridge_version") val bridgeVersion: String,
        @SerialName("target_os") val targetOs: String,
        @SerialName("target_arch") val targetArch: String,
        @SerialName("herdr_version") val herdrVersion: String,
    ) : ServerMessage

    @Serializable
    @SerialName("sessions")
    data class Sessions(val sessions: List<BridgeSessionDescriptor>) : ServerMessage

    @Serializable
    @SerialName("response")
    data class Response(val id: String, val session: String, val result: JsonElement) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(
        val id: String? = null,
        val session: String? = null,
        val code: String,
        val message: String,
    ) : ServerMessage

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val session: String, val epoch: String, val baseline: Boolean, val snapshot: JsonElement) : ServerMessage

    @Serializable
    @SerialName("agent_status")
    data class AgentStatus(val session: String, val epoch: String, @SerialName("pane_id") val paneId: String, val status: String) : ServerMessage

    @Serializable
    @SerialName("degraded")
    data class Degraded(
        val session: String,
        val epoch: String,
        val code: String,
        val message: String,
        @SerialName("uncovered_pane_ids") val uncoveredPaneIds: List<String>,
    ) : ServerMessage

    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(val epoch: String) : ServerMessage

    @Serializable
    @SerialName("closed")
    data class Closed(val id: String) : ServerMessage
}

@Serializable
@SerialName("request")
internal data class ClientRequest(
    val id: String,
    val session: String,
    val method: String,
    val params: JsonElement,
    val type: String = "request",
    val protocol: Int = 1,
)
