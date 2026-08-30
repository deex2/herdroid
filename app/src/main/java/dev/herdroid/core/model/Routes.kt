package dev.herdroid.core.model

data class SshEndpointSummary(val hostname: String, val port: Int, val username: String) {
    val label: String get() = "$username@$hostname:$port"
}

data class SavedRouteSummary(
    val id: Long,
    val name: String,
    val target: SshEndpointSummary,
    val jump: SshEndpointSummary?,
    val usesHardwareKey: Boolean,
)
