package dev.herdroid.core.keyvault

import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.data.DeleteKeyMetadataResult
import dev.herdroid.core.data.KeyMetadataRepository
import dev.herdroid.core.data.NewKeyMetadata
import dev.herdroid.core.data.StoredKeyMetadata
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.SshKeyOrigin
import dev.herdroid.core.ssh.CreatedHardwareKey
import dev.herdroid.core.ssh.HardwareKeyOperations
import dev.herdroid.core.ssh.keys.SshPublicKeyCodec
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed interface DeleteKeyResult {
    data object Deleted : DeleteKeyResult
    data class Referenced(val routeNames: List<String>) : DeleteKeyResult
}

interface KeyVault {
    val keys: Flow<List<HardwareKeyMetadata>>
    suspend fun generate(name: String): HardwareKeyMetadata
    suspend fun importKey(name: String, document: ByteArray, passphrase: CharArray?): HardwareKeyMetadata
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long): DeleteKeyResult
}

internal class SshKeyVault @Inject constructor(
    private val metadata: KeyMetadataRepository,
    private val hardware: HardwareKeyOperations,
    @Dispatcher(HerdroidDispatchers.IO) private val io: CoroutineDispatcher,
) : KeyVault {
    override val keys: Flow<List<HardwareKeyMetadata>> =
        metadata.keys.map { rows -> rows.map(::toHardwareKeyMetadata) }.flowOn(io)

    override suspend fun generate(name: String): HardwareKeyMetadata = withContext(io) {
        create(name, SshKeyOrigin.GENERATED, hardware::generate)
    }

    override suspend fun importKey(
        name: String,
        document: ByteArray,
        passphrase: CharArray?,
    ): HardwareKeyMetadata = try {
        withContext(io) {
            create(name, SshKeyOrigin.IMPORTED) { alias ->
                hardware.importKey(alias, document, passphrase)
            }
        }
    } finally {
        document.fill(0)
        passphrase?.fill('\u0000')
    }

    override suspend fun rename(id: Long, name: String) = withContext(io) {
        metadata.rename(id, SshPublicKeyCodec.normalizeComment(name))
    }

    override suspend fun delete(id: Long): DeleteKeyResult = withContext(io) {
        when (val result = metadata.delete(id, hardware::delete)) {
            DeleteKeyMetadataResult.Deleted -> DeleteKeyResult.Deleted
            is DeleteKeyMetadataResult.Referenced -> DeleteKeyResult.Referenced(result.routeNames)
        }
    }

    private suspend fun create(
        name: String,
        origin: SshKeyOrigin,
        createHardwareKey: (String) -> CreatedHardwareKey,
    ): HardwareKeyMetadata {
        val normalizedName = SshPublicKeyCodec.normalizeComment(name)
        currentCoroutineContext().ensureActive()
        val allocatedAlias = hardware.newAlias()
        val created = createHardwareKey(allocatedAlias)
        created.use {
            var metadataInserted = false
            try {
                require(created.alias == allocatedAlias) { "Hardware key alias mismatch" }
                currentCoroutineContext().ensureActive()
                val publicKey = created.publicKeyOpenSsh()
                val input = NewKeyMetadata(
                    name = normalizedName,
                    alias = created.alias,
                    publicKeyOpenSsh = publicKey,
                    fingerprint = created.fingerprint,
                    origin = origin,
                    securityLevel = created.securityLevel,
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
                val stored = input.use { metadata.insert(it) }
                metadataInserted = true
                return toHardwareKeyMetadata(stored)
            } catch (failure: Throwable) {
                if (!metadataInserted) {
                    try {
                        hardware.delete(created.alias)
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
        }
    }

    private fun toHardwareKeyMetadata(row: StoredKeyMetadata): HardwareKeyMetadata {
        val publicKey = Base64.getDecoder().decode(row.publicKeyBase64)
        return try {
            HardwareKeyMetadata(
                id = row.id,
                name = row.name,
                fingerprint = row.fingerprint,
                origin = row.origin,
                securityLevel = row.securityLevel,
                createdAtEpochMillis = row.createdAtEpochMillis,
                routeUseCount = row.routeUseCount,
                authorizedKeyLine = SshPublicKeyCodec.authorizedKeyLine(publicKey, row.name),
            )
        } finally {
            publicKey.fill(0)
        }
    }
}
