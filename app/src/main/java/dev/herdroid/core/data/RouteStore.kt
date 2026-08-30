package dev.herdroid.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.withTransaction
import dev.herdroid.core.data.db.EndpointEntity
import dev.herdroid.core.data.db.EndpointWithKey
import dev.herdroid.core.data.db.HerdroidDatabase
import dev.herdroid.core.data.db.KeyMetadataRow
import dev.herdroid.core.data.db.KnownHostEntity
import dev.herdroid.core.data.db.RouteEntity
import dev.herdroid.core.data.db.RouteWithEndpoints
import dev.herdroid.core.data.db.SshKeyEntity
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.model.SavedRouteSummary
import dev.herdroid.core.model.SshEndpointSummary
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Dao
internal interface RouteDao {
    @Transaction
    @Query("SELECT * FROM routes ORDER BY name, id")
    fun observeRoutes(): Flow<List<RouteWithEndpoints>>

    @Transaction
    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun findRoute(id: Long): RouteWithEndpoints?

    @Insert
    suspend fun insertEndpoint(endpoint: EndpointEntity): Long

    @Update
    suspend fun updateEndpoint(endpoint: EndpointEntity)

    @Query("DELETE FROM endpoints WHERE id IN (:ids)")
    suspend fun deleteEndpoints(ids: List<Long>)

    @Insert
    suspend fun insertRoute(route: RouteEntity): Long

    @Update
    suspend fun updateRoute(route: RouteEntity)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteRoute(id: Long)

    @Query(
        """SELECT ssh_keys.*, COUNT(DISTINCT routes.id) AS routeUseCount
           FROM ssh_keys
           LEFT JOIN endpoints ON endpoints.keyId = ssh_keys.id
           LEFT JOIN routes ON routes.targetEndpointId = endpoints.id OR routes.jumpEndpointId = endpoints.id
           GROUP BY ssh_keys.id
           ORDER BY ssh_keys.name, ssh_keys.id""",
    )
    fun observeKeys(): Flow<List<KeyMetadataRow>>

    @Query(
        """SELECT ssh_keys.*, COUNT(DISTINCT routes.id) AS routeUseCount
           FROM ssh_keys
           LEFT JOIN endpoints ON endpoints.keyId = ssh_keys.id
           LEFT JOIN routes ON routes.targetEndpointId = endpoints.id OR routes.jumpEndpointId = endpoints.id
           WHERE ssh_keys.id = :id
           GROUP BY ssh_keys.id""",
    )
    suspend fun findKey(id: Long): KeyMetadataRow?

    @Insert
    suspend fun insertKey(key: SshKeyEntity): Long

    @Query("UPDATE ssh_keys SET name = :name WHERE id = :id")
    suspend fun renameKey(id: Long, name: String)

    @Query(
        """SELECT DISTINCT routes.name FROM routes
           JOIN endpoints ON endpoints.id = routes.targetEndpointId OR endpoints.id = routes.jumpEndpointId
           WHERE endpoints.keyId = :id
           ORDER BY routes.name""",
    )
    suspend fun keyRouteNames(id: Long): List<String>

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteKey(id: Long)

    @Query("SELECT COUNT(*) FROM endpoints")
    suspend fun endpointCount(): Int

    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port")
    suspend fun findKnownHosts(hostname: String, port: Int): List<KnownHostEntity>

    @Upsert
    suspend fun upsertKnownHost(host: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE hostname = :hostname AND port = :port AND algorithm = :algorithm AND keyBase64 = :keyBase64")
    suspend fun deleteKnownHost(hostname: String, port: Int, algorithm: String, keyBase64: String)

    @Query(
        """UPDATE endpoints
           SET cachedBridgeTarget = :target, cachedHerdrPath = :herdrPath, cachedBridgePath = :bridgePath
           WHERE id = (SELECT targetEndpointId FROM routes WHERE id = :routeId)""",
    )
    suspend fun updateBridgeCache(routeId: Long, target: String?, herdrPath: String?, bridgePath: String?)
}

@Singleton
internal class RouteStore private constructor(
    private val database: HerdroidDatabase?,
    private val routeDao: RouteDao?,
    private val afterKeyInsert: () -> Unit,
    private val afterAliasDelete: suspend () -> Unit,
    private val afterKeyCommit: suspend () -> Unit,
    private val isAvailable: () -> Boolean,
    private val processState: ProcessDatabaseState? = null,
) : RouteRepository, ConnectionRouteRepository, KeyMetadataRepository {
    @Inject
    internal constructor(state: ProcessDatabaseState) : this(
        null,
        null,
        {},
        {},
        {},
        { state.availability.value == LocalDataAvailability.Available },
        state,
    )

    internal constructor(database: HerdroidDatabase) : this(database, database.routeDao(), {}, {}, {}, { true })

    internal constructor(database: HerdroidDatabase, isAvailable: () -> Boolean) :
        this(database, database.routeDao(), {}, {}, {}, isAvailable)

    internal constructor(dao: RouteDao) : this(null, dao, {}, {}, {}, { true })

    internal constructor(
        database: HerdroidDatabase,
        afterKeyInsert: () -> Unit,
        afterAliasDelete: suspend () -> Unit = {},
        afterKeyCommit: suspend () -> Unit = {},
        isAvailable: () -> Boolean = { true },
    ) : this(database, database.routeDao(), afterKeyInsert, afterAliasDelete, afterKeyCommit, isAvailable)

    override val routes: Flow<List<SavedRouteSummary>> = flow {
        withAvailableData {
            emitAll(dao().observeRoutes().map { rows ->
                try {
                    rows.map(RouteWithEndpoints::toSummary)
                } finally {
                    rows.forEach(RouteWithEndpoints::wipeDatabasePasswords)
                }
            })
        }
    }

    override suspend fun findEditable(routeId: Long): EditableRoute? = withAvailableData {
        dao().findRoute(routeId)?.let { row ->
            try {
                row.toEditable()
            } finally {
                row.wipeDatabasePasswords()
            }
        }
    }

    override suspend fun loadForConnection(routeId: Long): ConnectionRouteInput = withAvailableData {
        val row = dao().findRoute(routeId) ?: throw RouteNotFoundException(routeId)
        try {
            row.toConnectionInput()
        } finally {
            row.wipeDatabasePasswords()
        }
    }

    override suspend fun knownHosts(hostname: String, port: Int): List<KnownHostRecord> =
        withAvailableData { dao().findKnownHosts(hostname, port).map(KnownHostEntity::toDomain) }

    override suspend fun save(input: RouteWriteInput): Long = inTransaction {
        if (input.id == 0L) {
            val targetId = insertEndpoint(input.target)
            val jumpId = input.jump?.let { insertEndpoint(it) }
            dao().insertRoute(RouteEntity(name = input.name, targetEndpointId = targetId, jumpEndpointId = jumpId))
        } else {
            val current = requireNotNull(dao().findRoute(input.id)) { "Unknown route id" }
            try {
                updateEndpoint(input.target, current.target.endpoint.id)
                val jumpId = when {
                    input.jump == null -> null
                    current.jump == null -> insertEndpoint(input.jump)
                    else -> current.jump.endpoint.id.also { updateEndpoint(input.jump, it) }
                }
                dao().updateRoute(RouteEntity(input.id, input.name, current.target.endpoint.id, jumpId))
                if (input.jump == null && current.jump != null) {
                    dao().deleteEndpoints(listOf(current.jump.endpoint.id))
                }
                input.id
            } finally {
                current.wipeDatabasePasswords()
            }
        }
    }

    override suspend fun delete(routeId: Long) = inTransaction {
        val route = dao().findRoute(routeId) ?: return@inTransaction
        try {
            dao().deleteRoute(routeId)
            dao().deleteEndpoints(listOfNotNull(route.target.endpoint.id, route.jump?.endpoint?.id))
        } finally {
            route.wipeDatabasePasswords()
        }
    }

    override suspend fun updateKnownHost(record: KnownHostRecord) =
        withAvailableData { dao().upsertKnownHost(record.toEntity()) }

    override suspend fun deleteKnownHost(candidate: HostKeyCandidate) = withAvailableData {
        dao().deleteKnownHost(
            candidate.hostname,
            candidate.port,
            candidate.algorithm,
            candidate.keyBase64,
        )
    }

    override suspend fun updateBridgeCache(routeId: Long, cache: BridgeLaunchCache?) = withAvailableData {
        dao().updateBridgeCache(routeId, cache?.target, cache?.herdrPath, cache?.bridgePath)
    }

    override val keys: Flow<List<StoredKeyMetadata>> = flow {
        withAvailableData {
            emitAll(dao().observeKeys().map { rows -> rows.map(KeyMetadataRow::toStoredMetadata) })
        }
    }

    override suspend fun insert(input: NewKeyMetadata): StoredKeyMetadata {
        val callerJob = currentCoroutineContext()[Job]
        callerJob?.ensureActive()
        processState?.awaitInitialized()
        val transactionCopy = input.copyPublicKeyForTransaction()
        try {
            return withContext(NonCancellable) {
                val saved = inTransaction {
                    val id = try {
                        dao().insertKey(
                            SshKeyEntity(
                                name = input.name,
                                alias = input.alias,
                                publicKeyOpenSsh = transactionCopy,
                                fingerprint = input.fingerprint,
                                origin = input.origin,
                                securityLevel = input.securityLevel,
                                createdAtEpochMillis = input.createdAtEpochMillis,
                            ),
                        )
                    } catch (failure: Exception) {
                        throw mapDuplicateName(failure)
                    }
                    afterKeyInsert()
                    val inserted = requireNotNull(dao().findKey(id)) { "Inserted key metadata is missing" }
                    callerJob?.ensureActive()
                    inserted.toStoredMetadata()
                }
                afterKeyCommit()
                saved
            }
        } finally {
            transactionCopy.fill(0)
        }
    }

    override suspend fun find(id: Long): StoredKeyMetadata? =
        withAvailableData { dao().findKey(id)?.toStoredMetadata() }

    override suspend fun rename(id: Long, name: String) = withAvailableData {
        try {
            dao().renameKey(id, name)
        } catch (failure: Exception) {
            throw mapDuplicateName(failure)
        }
    }

    override suspend fun delete(id: Long, deleteAlias: (String) -> Unit): DeleteKeyMetadataResult {
        val callerJob = currentCoroutineContext()[Job]
        callerJob?.ensureActive()
        processState?.awaitInitialized()
        return withContext(NonCancellable) {
            inTransaction {
                val key = requireNotNull(dao().findKey(id)) { "Unknown key id" }
                val references = dao().keyRouteNames(id).distinct().sorted()
                if (references.isNotEmpty()) {
                    DeleteKeyMetadataResult.Referenced(references)
                } else {
                    callerJob?.ensureActive()
                    deleteAlias(key.alias)
                    afterAliasDelete()
                    dao().deleteKey(id)
                    DeleteKeyMetadataResult.Deleted
                }
            }
        }
    }

    private suspend fun insertEndpoint(input: EndpointWriteInput): Long {
        val entity = input.toEntity()
        return try {
            dao().insertEndpoint(entity)
        } finally {
            entity.password?.fill(0)
        }
    }

    private suspend fun updateEndpoint(input: EndpointWriteInput, id: Long) {
        val entity = input.toEntity(id)
        try {
            dao().updateEndpoint(entity)
        } finally {
            entity.password?.fill(0)
        }
    }

    private suspend fun <T> inTransaction(block: suspend () -> T): T = withAvailableData {
        activeDatabase()?.withTransaction {
            requireAvailable()
            block()
        } ?: block()
    }

    private suspend fun <T> withAvailableData(block: suspend () -> T): T {
        processState?.awaitInitialized()
        requireAvailable()
        return try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (!isAvailable() && failure.isClosedDatabaseFailure()) throw LocalDataUnavailableException()
            throw failure
        }
    }

    private fun requireAvailable() {
        if (!isAvailable()) throw LocalDataUnavailableException()
    }

    private fun dao(): RouteDao {
        requireAvailable()
        return processState?.database?.routeDao() ?: routeDao ?: throw LocalDataUnavailableException()
    }

    private fun activeDatabase() = processState?.database ?: database
}

private fun Throwable.isClosedDatabaseFailure() = generateSequence(this, Throwable::cause)
    .mapNotNull(Throwable::message)
    .any { message ->
        message == "Database helper is closed" ||
            message.contains("connection pool has been closed", ignoreCase = true) ||
            message.contains("closed connection", ignoreCase = true)
    }

private fun mapDuplicateName(failure: Exception): Exception =
    if (generateSequence(failure as Throwable?) { it.cause }
            .any { it.message.orEmpty().contains("UNIQUE constraint failed: ssh_keys.name") }
    ) {
        DuplicateKeyNameException(failure)
    } else {
        failure
    }

private fun EndpointWriteInput.toEntity(id: Long = 0): EndpointEntity = when (val auth = authentication) {
    is EndpointAuthenticationInput.Password -> EndpointEntity(
        id, hostname, port, username, "password", auth.copyForTransaction(), null, herdrPath,
    )
    is EndpointAuthenticationInput.HardwareKey -> EndpointEntity(
        id, hostname, port, username, "hardware_key", null, auth.keyId, herdrPath,
    )
}

private fun EndpointWithKey.toConnectionInput(): ConnectionEndpointInput {
    val authentication = when (endpoint.authType) {
        "password" -> ConnectionAuthenticationInput.Password(requireNotNull(endpoint.password).copyOf())
        "hardware_key" -> requireNotNull(key) { "Hardware key metadata is missing" }.let { metadata ->
            val publicKey = metadata.publicKeyOpenSsh.copyOf()
            try {
                ConnectionAuthenticationInput.HardwareKey(metadata.id, metadata.alias, publicKey)
            } finally {
                publicKey.fill(0)
            }
        }
        else -> error("Unsupported authentication type")
    }
    return ConnectionEndpointInput(
        endpoint.hostname,
        endpoint.port,
        endpoint.username,
        authentication,
        endpoint.herdrPath,
        endpoint.run {
            if (cachedBridgeTarget != null && cachedHerdrPath != null && cachedBridgePath != null) {
                BridgeLaunchCache(cachedBridgeTarget, cachedHerdrPath, cachedBridgePath)
            } else {
                null
            }
        },
    )
}

internal fun RouteWithEndpoints.toConnectionInput(
    onTargetCreated: (ConnectionEndpointInput) -> Unit = {},
): ConnectionRouteInput {
    val targetInput = target.toConnectionInput()
    return try {
        onTargetCreated(targetInput)
        ConnectionRouteInput(route.id, route.name, targetInput, jump?.toConnectionInput())
    } catch (failure: Throwable) {
        targetInput.close()
        throw failure
    }
}

private fun RouteWithEndpoints.toEditable() = EditableRoute(
    route.id,
    route.name,
    target.toEditable(),
    jump?.toEditable(),
)

private fun EndpointWithKey.toEditable() = EditableEndpoint(
    endpoint.hostname,
    endpoint.port,
    endpoint.username,
    endpoint.keyId,
    endpoint.herdrPath,
)

private fun RouteWithEndpoints.toSummary() = SavedRouteSummary(
    route.id,
    route.name,
    SshEndpointSummary(target.endpoint.hostname, target.endpoint.port, target.endpoint.username),
    jump?.endpoint?.let { SshEndpointSummary(it.hostname, it.port, it.username) },
    target.endpoint.keyId != null,
)

private fun RouteWithEndpoints.wipeDatabasePasswords() {
    target.endpoint.password?.fill(0)
    jump?.endpoint?.password?.fill(0)
}

private fun KeyMetadataRow.toStoredMetadata(): StoredKeyMetadata {
    val publicKey = publicKeyOpenSsh.copyOf()
    return try {
        StoredKeyMetadata(
            id,
            name,
            alias,
            Base64.getEncoder().encodeToString(publicKey),
            fingerprint,
            origin,
            securityLevel,
            createdAtEpochMillis,
            routeUseCount,
        )
    } finally {
        publicKey.fill(0)
    }
}

private fun KnownHostRecord.toEntity() =
    KnownHostEntity(hostname, port, algorithm, keyBase64, acceptedAtEpochMillis)

private fun KnownHostEntity.toDomain() =
    KnownHostRecord(hostname, port, algorithm, keyBase64, acceptedAtEpochMillis)
