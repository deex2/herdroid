package dev.herdroid.core.data

import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin
import kotlinx.coroutines.flow.Flow

class NewKeyMetadata(
    val name: String,
    val alias: String,
    publicKeyOpenSsh: ByteArray,
    val fingerprint: String,
    val origin: SshKeyOrigin,
    val securityLevel: HardwareSecurityLevel,
    val createdAtEpochMillis: Long,
) : AutoCloseable {
    // Ownership transfers to this input; the caller must not retain or reuse it.
    private val ownedPublicKey = publicKeyOpenSsh
    fun copyPublicKeyForTransaction(): ByteArray = ownedPublicKey.copyOf()
    override fun close() = ownedPublicKey.fill(0)
}

sealed interface DeleteKeyMetadataResult {
    data object Deleted : DeleteKeyMetadataResult
    data class Referenced(val routeNames: List<String>) : DeleteKeyMetadataResult
}

data class StoredKeyMetadata(
    val id: Long,
    val name: String,
    val alias: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val origin: SshKeyOrigin,
    val securityLevel: HardwareSecurityLevel,
    val createdAtEpochMillis: Long,
    val routeUseCount: Int,
)

interface KeyMetadataRepository {
    val keys: Flow<List<StoredKeyMetadata>>
    suspend fun insert(input: NewKeyMetadata): StoredKeyMetadata
    suspend fun find(id: Long): StoredKeyMetadata?
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long, deleteAlias: (String) -> Unit): DeleteKeyMetadataResult
}

class DuplicateKeyNameException(cause: Throwable) :
    IllegalArgumentException("A key with this name already exists.", cause)
