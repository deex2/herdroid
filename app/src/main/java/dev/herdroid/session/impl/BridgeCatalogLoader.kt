package dev.herdroid.session.impl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.herdr.BridgeArtifact
import dev.herdroid.core.herdr.BridgeArtifactCatalog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class BridgeCatalogLoader internal constructor(
    private val readAsset: (String) -> ByteArray,
    @Dispatcher(HerdroidDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Dispatcher(HerdroidDispatchers.IO) ioDispatcher: CoroutineDispatcher,
    ) : this(
        readAsset = { path -> context.assets.open(path).use { it.readBytes() } },
        ioDispatcher = ioDispatcher,
    )

    suspend fun load(): BridgeArtifactCatalog = withContext(ioDispatcher) {
        val raw = readAsset("bridge/catalog.json").decodeToString()
        val validated = BridgeArtifactCatalog.parse(raw)
        val artifacts = validated.entries.associate { entry ->
            entry.target to BridgeArtifact(
                readAsset("bridge/${entry.binaryPath}"),
                readAsset("bridge/${entry.manifestPath}"),
            )
        }
        BridgeArtifactCatalog.parse(raw, artifacts)
    }
}
