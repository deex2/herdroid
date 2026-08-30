package dev.herdroid.core.model

enum class Hop { JUMP, TARGET }

data class KnownHostRecord(
    val hostname: String,
    val port: Int,
    val algorithm: String,
    val keyBase64: String,
    val acceptedAtEpochMillis: Long,
)

data class HostKeyCandidate(
    val hop: Hop,
    val hostname: String,
    val port: Int,
    val algorithm: String,
    val sha256: String,
    val keyBase64: String,
) {
    fun toKnownHostRecord(acceptedAtEpochMillis: Long) = KnownHostRecord(
        hostname = hostname,
        port = port,
        algorithm = algorithm,
        keyBase64 = keyBase64,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
    )
}

sealed interface HostKeyDecision {
    data object Accept : HostKeyDecision
    data class Ask(val candidate: HostKeyCandidate) : HostKeyDecision
    data class RejectChanged(val expected: HostKeyCandidate, val actual: HostKeyCandidate) : HostKeyDecision
}
