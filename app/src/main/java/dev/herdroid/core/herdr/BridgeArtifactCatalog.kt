package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.CatalogDto
import dev.herdroid.core.model.RemoteOperatingSystem
import java.security.MessageDigest
import kotlinx.serialization.json.Json

class BridgeArtifact(binary: ByteArray, manifest: ByteArray) {
    private val binaryBytes = binary.copyOf()
    private val manifestBytes = manifest.copyOf()

    fun binary(): ByteArray = binaryBytes.copyOf()
    fun manifest(): ByteArray = manifestBytes.copyOf()
}

data class BridgeCatalogEntry(
    val target: String,
    val sha256: String,
    val binaryPath: String,
    val manifestPath: String,
)

enum class CatalogMode { DEVELOPMENT, RELEASE }

class BridgeArtifactCatalog private constructor(
    val pluginVersion: String,
    val minHerdrVersion: String,
    val protocol: Int,
    val entries: List<BridgeCatalogEntry>,
    private val artifacts: Map<String, BridgeArtifact>,
) {
    fun artifactFor(os: RemoteOperatingSystem, architecture: String): BridgeArtifact {
        val target = targetFor(os, architecture)
        val entry = entries.singleOrNull { it.target == target }
            ?: throw IllegalArgumentException("No bridge artifact for $target")
        val artifact = artifacts[target] ?: throw IllegalStateException("Bridge bytes are unavailable for $target")
        require(sha256(artifact.binary()) == entry.sha256) { "Bridge artifact hash mismatch" }
        validateManifest(artifact.manifest())
        return BridgeArtifact(artifact.binary(), artifact.manifest())
    }

    fun expectedShaFor(os: RemoteOperatingSystem, architecture: String): String = entries.single {
        it.target == targetFor(os, architecture)
    }.sha256

    fun hasTrustedManifest(bytes: ByteArray): Boolean = normalizedManifest(bytes) == trustedManifest

    fun targetFor(os: RemoteOperatingSystem, architecture: String): String = when (os to architecture.lowercase()) {
        RemoteOperatingSystem.LINUX to "x86_64" -> LINUX_X64
        RemoteOperatingSystem.LINUX to "aarch64" -> LINUX_ARM64
        RemoteOperatingSystem.WINDOWS to "amd64", RemoteOperatingSystem.WINDOWS to "x86_64" -> WINDOWS_X64
        else -> throw IllegalArgumentException("Unsupported target $os/$architecture")
    }

    companion object {
        const val PLUGIN_ID = "dev.herdroid.bridge"
        const val PLUGIN_VERSION = "0.1.0"
        const val MIN_HERDR_VERSION = "0.8.0"
        const val PROTOCOL = 1
        const val LINUX_X64 = "x86_64-unknown-linux-gnu"
        const val LINUX_ARM64 = "aarch64-unknown-linux-gnu"
        const val WINDOWS_X64 = "x86_64-pc-windows-msvc"
        val targets = setOf(LINUX_X64, LINUX_ARM64, WINDOWS_X64)
        private val json = Json { ignoreUnknownKeys = false }
        private val trustedManifest = """id = "dev.herdroid.bridge"
name = "Herdroid Bridge"
version = "0.1.0"
min_herdr_version = "0.8.0"
description = "SSH-stdio companion for the Herdroid Android client"
platforms = ["linux", "windows"]"""

        fun parse(
            raw: String,
            artifacts: Map<String, BridgeArtifact> = emptyMap(),
            mode: CatalogMode = CatalogMode.RELEASE,
        ): BridgeArtifactCatalog {
            val value = json.decodeFromString<CatalogDto>(raw)
            require(value.pluginId == PLUGIN_ID) { "Unexpected bridge plugin id" }
            require(value.protocol == PROTOCOL) { "Unsupported catalog protocol" }
            require(value.pluginVersion == PLUGIN_VERSION && value.minHerdrVersion == MIN_HERDR_VERSION) { "Unexpected bridge version pin" }
            val entries = value.targets.map { item ->
                require(item.target in targets && item.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid bridge artifact" }
                require(item.manifest == "${item.target}/herdr-plugin.toml" && item.binary == binaryPath(item.target)) {
                    "Invalid bridge artifact paths"
                }
                BridgeCatalogEntry(item.target, item.sha256, item.binary, item.manifest)
            }
            require(entries.map { it.target }.toSet().size == entries.size) { "Duplicate bridge artifact" }
            if (mode == CatalogMode.RELEASE) require(entries.map { it.target }.toSet() == targets) { "Incomplete release bridge catalog" }
            if (artifacts.isNotEmpty()) require(artifacts.keys == entries.map { it.target }.toSet()) { "Bridge bytes do not match catalog" }
            val snapshots = artifacts.mapValues { (_, artifact) -> BridgeArtifact(artifact.binary(), artifact.manifest()) }
            val catalog = BridgeArtifactCatalog(value.pluginVersion, value.minHerdrVersion, value.protocol, entries, snapshots)
            snapshots.keys.forEach { target -> catalog.artifactFor(targetOs(target), targetArch(target)) }
            return catalog
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun binaryPath(target: String) = "$target/bin/herdroid-bridge" + if (target == WINDOWS_X64) ".exe" else ""

        private fun validateManifest(manifest: ByteArray) {
            require(normalizedManifest(manifest) == trustedManifest) { "Untrusted bridge manifest" }
        }

        private fun normalizedManifest(manifest: ByteArray) = manifest.decodeToString()
            .replace("\r\n", "\n")
            .removeSuffix("\n")

        private fun targetOs(target: String) =
            if (target == WINDOWS_X64) RemoteOperatingSystem.WINDOWS else RemoteOperatingSystem.LINUX
        private fun targetArch(target: String) = when (target) {
            LINUX_ARM64 -> "aarch64"
            WINDOWS_X64 -> "amd64"
            else -> "x86_64"
        }
    }
}
