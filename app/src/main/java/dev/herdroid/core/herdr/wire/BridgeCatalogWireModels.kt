package dev.herdroid.core.herdr.wire

import kotlinx.serialization.Serializable

@Serializable
internal data class CatalogDto(
    val plugin_id: String,
    val plugin_version: String,
    val min_herdr_version: String,
    val protocol: Int,
    val targets: List<CatalogTargetDto>,
) {
    val pluginId get() = plugin_id
    val pluginVersion get() = plugin_version
    val minHerdrVersion get() = min_herdr_version
}

@Serializable
internal data class CatalogTargetDto(
    val target: String,
    val sha256: String,
    val binary: String,
    val manifest: String,
)
