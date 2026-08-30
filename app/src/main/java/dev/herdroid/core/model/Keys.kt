package dev.herdroid.core.model

enum class SshKeyOrigin { GENERATED, IMPORTED }

enum class HardwareSecurityLevel { TEE, STRONGBOX }

data class HardwareKeyMetadata(
    val id: Long,
    val name: String,
    val fingerprint: String,
    val origin: SshKeyOrigin,
    val securityLevel: HardwareSecurityLevel,
    val createdAtEpochMillis: Long,
    val routeUseCount: Int,
    val authorizedKeyLine: String,
)
