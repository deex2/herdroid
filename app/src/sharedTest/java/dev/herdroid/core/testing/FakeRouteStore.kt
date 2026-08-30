package dev.herdroid.core.testing

import dev.herdroid.core.data.ConnectionRouteInput
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.BridgeLaunchCache
import dev.herdroid.core.data.DeleteKeyMetadataResult
import dev.herdroid.core.data.EditableEndpoint
import dev.herdroid.core.data.EditableRoute
import dev.herdroid.core.data.EndpointAuthenticationInput
import dev.herdroid.core.data.KeyMetadataRepository
import dev.herdroid.core.data.NewKeyMetadata
import dev.herdroid.core.data.RouteNotFoundException
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteWriteInput
import dev.herdroid.core.data.StoredKeyMetadata
import dev.herdroid.core.keyvault.DeleteKeyResult
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.model.SavedRouteSummary
import dev.herdroid.core.model.SshEndpointSummary
import dev.herdroid.core.model.SshKeyOrigin
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeRouteStore(
    routes: List<SavedRouteSummary> = emptyList(),
) : RouteRepository, ConnectionRouteRepository {
    private val mutableRoutes = MutableStateFlow(routes)
    override val routes = mutableRoutes.asStateFlow()
    private val editableRoutes = mutableMapOf<Long, EditableRoute>()
    private val nextRouteId = AtomicLong((routes.maxOfOrNull(SavedRouteSummary::id) ?: 0L) + 1)

    val saveCalls = AtomicInteger()
    val deleteCalls = AtomicInteger()
    val connectionLoadCalls = AtomicInteger()

    fun setRoutes(routes: List<SavedRouteSummary>) {
        mutableRoutes.value = routes
    }

    fun seedEditable(route: EditableRoute) {
        editableRoutes[route.id] = route
    }

    override suspend fun findEditable(routeId: Long): EditableRoute? = editableRoutes[routeId]

    override suspend fun save(input: RouteWriteInput): Long {
        saveCalls.incrementAndGet()
        val id = input.id.takeIf { it > 0 } ?: nextRouteId.getAndIncrement()
        val target = SshEndpointSummary(input.target.hostname, input.target.port, input.target.username)
        val jump = input.jump?.let { SshEndpointSummary(it.hostname, it.port, it.username) }
        val usesHardwareKey = input.target.authentication is EndpointAuthenticationInput.HardwareKey ||
            input.jump?.authentication is EndpointAuthenticationInput.HardwareKey
        val summary = SavedRouteSummary(id, input.name, target, jump, usesHardwareKey)
        editableRoutes[id] = EditableRoute(
            id = id,
            name = input.name,
            target = input.target.toEditable(),
            jump = input.jump?.toEditable(),
        )
        mutableRoutes.update { current -> current.filterNot { it.id == id } + summary }
        return id
    }

    override suspend fun delete(routeId: Long) {
        deleteCalls.incrementAndGet()
        editableRoutes.remove(routeId)
        mutableRoutes.update { routes -> routes.filterNot { it.id == routeId } }
    }

    override suspend fun loadForConnection(routeId: Long): ConnectionRouteInput {
        connectionLoadCalls.incrementAndGet()
        throw RouteNotFoundException(routeId)
    }

    override suspend fun knownHosts(hostname: String, port: Int): List<KnownHostRecord> = unsupportedKnownHosts()
    override suspend fun updateKnownHost(record: KnownHostRecord) = unsupportedKnownHosts()
    override suspend fun deleteKnownHost(candidate: HostKeyCandidate) = unsupportedKnownHosts()
    override suspend fun updateBridgeCache(routeId: Long, cache: BridgeLaunchCache?) = Unit

    private fun unsupportedKnownHosts(): Nothing = throw UnsupportedOperationException("FakeRouteStore does not support known hosts")

    private fun dev.herdroid.core.data.EndpointWriteInput.toEditable() = EditableEndpoint(
        hostname = hostname,
        port = port,
        username = username,
        keyId = (authentication as? EndpointAuthenticationInput.HardwareKey)?.keyId,
        herdrPath = herdrPath,
    )

}

class FakeKeyMetadataRepository : KeyMetadataRepository {
    override val keys = MutableStateFlow<List<StoredKeyMetadata>>(emptyList()).asStateFlow()
    override suspend fun insert(input: NewKeyMetadata): StoredKeyMetadata = error("Fake key metadata is read-only")
    override suspend fun find(id: Long): StoredKeyMetadata? = null
    override suspend fun rename(id: Long, name: String) = error("Fake key metadata is read-only")
    override suspend fun delete(id: Long, deleteAlias: (String) -> Unit): DeleteKeyMetadataResult =
        error("Fake key metadata is read-only")
}

class FakeKeyVault(
    keys: List<HardwareKeyMetadata> = emptyList(),
    private val generatedRouteUseCount: Int = 0,
) : KeyVault {
    private val mutableKeys = MutableStateFlow(keys)
    private val nextCollectionBarrier = AtomicReference<CompletableDeferred<Unit>?>(null)
    val activeCollectors = AtomicInteger()
    val maximumCollectors = AtomicInteger()
    val collectorSubscriptions = AtomicInteger()
    override val keys: StateFlow<List<HardwareKeyMetadata>> = CountingStateFlow(
        delegate = mutableKeys,
        activeCollectors = activeCollectors,
        maximumCollectors = maximumCollectors,
        collectorSubscriptions = collectorSubscriptions,
        beforeCollect = { nextCollectionBarrier.getAndSet(null)?.await() },
    )
    private val nextId = AtomicLong((keys.maxOfOrNull(HardwareKeyMetadata::id) ?: 0L) + 1)
    private val references = mutableMapOf<Long, List<String>>()

    var importedDocument: ByteArray? = null
        private set
    var importedDocumentCopy: ByteArray? = null
        private set
    var importedPassphrase: CharArray? = null
        private set
    var importedPassphraseCopy: CharArray? = null
        private set

    fun setKeys(keys: List<HardwareKeyMetadata>) {
        mutableKeys.value = keys
    }

    fun pauseNextCollection(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { barrier ->
        check(nextCollectionBarrier.compareAndSet(null, barrier)) { "A key collection is already paused" }
    }

    fun setReferences(id: Long, routeNames: List<String>) {
        references[id] = routeNames
    }

    fun clearCapturedImport() {
        importedDocument?.fill(0)
        importedDocumentCopy?.fill(0)
        importedPassphrase?.fill('\u0000')
        importedPassphraseCopy?.fill('\u0000')
        importedDocument = null
        importedDocumentCopy = null
        importedPassphrase = null
        importedPassphraseCopy = null
    }

    override suspend fun generate(name: String): HardwareKeyMetadata = create(name, SshKeyOrigin.GENERATED)

    override suspend fun importKey(
        name: String,
        document: ByteArray,
        passphrase: CharArray?,
    ): HardwareKeyMetadata = try {
        importedDocument = document
        importedDocumentCopy = document.copyOf()
        importedPassphrase = passphrase
        importedPassphraseCopy = passphrase?.copyOf()
        create(name, SshKeyOrigin.IMPORTED)
    } finally {
        document.fill(0)
        passphrase?.fill('\u0000')
    }

    override suspend fun rename(id: Long, name: String) {
        mutableKeys.update { keys ->
            keys.map { key ->
                if (key.id != id) key else key.copy(
                    name = name,
                    authorizedKeyLine = key.authorizedKeyLine.withCanonicalComment(name),
                )
            }
        }
    }

    override suspend fun delete(id: Long): DeleteKeyResult {
        references[id]?.takeIf(List<String>::isNotEmpty)?.let { return DeleteKeyResult.Referenced(it) }
        val key = mutableKeys.value.firstOrNull { it.id == id } ?: return DeleteKeyResult.Deleted
        if (key.routeUseCount > 0) return DeleteKeyResult.Referenced(listOf("route"))
        mutableKeys.update { keys -> keys.filterNot { it.id == id } }
        return DeleteKeyResult.Deleted
    }

    private fun create(name: String, origin: SshKeyOrigin): HardwareKeyMetadata {
        val id = nextId.getAndIncrement()
        val key = HardwareKeyMetadata(
            id = id,
            name = name,
            fingerprint = "SHA256:test$id",
            origin = origin,
            securityLevel = HardwareSecurityLevel.TEE,
            createdAtEpochMillis = id,
            routeUseCount = generatedRouteUseCount,
            authorizedKeyLine = "ecdsa-sha2-nistp256 TEST$id".withCanonicalComment(name),
        )
        mutableKeys.update { it + key }
        return key
    }

    private fun String.withCanonicalComment(name: String): String {
        val fields = split(' ', limit = 3)
        require(fields.size >= 2) { "Authorized key line must contain an algorithm and blob" }
        return "${fields[0]} ${fields[1]} herdroid:$name"
    }
}

private class CountingStateFlow<T>(
    private val delegate: StateFlow<T>,
    private val activeCollectors: AtomicInteger,
    private val maximumCollectors: AtomicInteger,
    private val collectorSubscriptions: AtomicInteger,
    private val beforeCollect: suspend () -> Unit,
) : StateFlow<T> by delegate {
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        collectorSubscriptions.incrementAndGet()
        val active = activeCollectors.incrementAndGet()
        maximumCollectors.updateAndGet { maxOf(it, active) }
        try {
            beforeCollect()
            delegate.collect(collector)
        } finally {
            activeCollectors.decrementAndGet()
        }
    }
}
