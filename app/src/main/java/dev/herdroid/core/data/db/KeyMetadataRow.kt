package dev.herdroid.core.data.db

import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin

internal data class KeyMetadataRow(
    val id: Long,
    val name: String,
    val alias: String,
    val publicKeyOpenSsh: ByteArray,
    val fingerprint: String,
    val origin: SshKeyOrigin,
    val securityLevel: HardwareSecurityLevel,
    val createdAtEpochMillis: Long,
    val routeUseCount: Int,
)
