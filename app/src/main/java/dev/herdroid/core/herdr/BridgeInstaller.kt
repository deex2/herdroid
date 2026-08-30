package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.remoteOperatingSystemFromWire
import dev.herdroid.core.herdr.wire.toWire
import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.ssh.BridgeTransport
import dev.herdroid.core.ssh.MAX_BRIDGE_OUTPUT_BYTES
import dev.herdroid.core.ssh.RemoteCommandResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface DiscoveryResult {
    data class Ready(
        val herdrPath: String,
        val os: RemoteOperatingSystem,
        val architecture: String,
    ) : DiscoveryResult

    data object ExplicitPathRequired : DiscoveryResult
}

sealed interface VerifiedInstall

data class BridgeLaunchDescriptor(
    val target: String,
    val os: RemoteOperatingSystem,
    val architecture: String,
    val herdrPath: String,
    val bridgePath: String,
)

class BridgeInstallPlan internal constructor(
    val approval: BridgeApproval,
    internal val owner: BridgeInstaller,
)

private data class VerifiedBridgeInstall(
    val owner: BridgeInstaller,
    val target: String,
    val os: RemoteOperatingSystem,
    val architecture: String,
    val root: String,
    val herdrPath: String,
) : VerifiedInstall

private data class StockPlugin(
    val version: String,
    val manifestPath: String,
    val root: String,
    val enabled: Boolean,
)

private data class BootstrapSnapshot(
    val herdrPath: String,
    val os: RemoteOperatingSystem,
    val architecture: String,
    val home: String,
    val plugins: String,
    val manifestSha: String?,
    val binarySha: String?,
)

private data class RollbackCandidate(
    val version: String,
    val manifestPath: String,
    val root: String,
)

private class RemoteCommandFailure : IllegalArgumentException("Remote command failed")

class BridgeInstaller(
    private val transport: BridgeTransport,
    private val catalog: BridgeArtifactCatalog,
) {
    private var bootstrap: BootstrapSnapshot? = null

    suspend fun discover(explicitHerdrPath: String? = null): DiscoveryResult {
        if (explicitHerdrPath != null) {
            val os = when {
                isAbsolute(RemoteOperatingSystem.LINUX, explicitHerdrPath) -> RemoteOperatingSystem.LINUX
                isAbsolute(RemoteOperatingSystem.WINDOWS, explicitHerdrPath) -> RemoteOperatingSystem.WINDOWS
                else -> return DiscoveryResult.ExplicitPathRequired
            }
            return discover(os, normalizeAbsolutePath(os, explicitHerdrPath))
        }
        for (os in RemoteOperatingSystem.entries) {
            val path = lookupHerdr(os) ?: continue
            return discover(os, path)
        }
        return DiscoveryResult.ExplicitPathRequired
    }

    suspend fun discover(os: RemoteOperatingSystem, explicitHerdrPath: String? = null): DiscoveryResult {
        bootstrap = null
        val herdrPath = explicitHerdrPath ?: lookupHerdr(os) ?: return DiscoveryResult.ExplicitPathRequired
        if (!isAbsolute(os, herdrPath)) return DiscoveryResult.ExplicitPathRequired
        val fields = command(RemoteCommands.bootstrap(os, herdrPath, catalog.pluginVersion)).split('\u0000')
        require(fields.size == BOOTSTRAP_FIELDS) { "Invalid bootstrap response" }
        val home = fields[0]
        val rawArchitecture = fields[1]
        val version = fields[2]
        val sessions = fields[3]
        val plugins = fields[4]
        parseHerdrVersion(version)
        parseBoundedJson(sessions)
            .jsonObject.getValue("sessions").jsonArray.forEach { item ->
                val objectValue = item.jsonObject
                val name = objectValue.getValue("name").jsonPrimitive.string()
                require(BridgeIdentifiers.validSession(name)) { "Invalid session name" }
                objectValue.getValue("running").jsonPrimitive.strictBoolean()
                objectValue.getValue("socket_path").jsonPrimitive.string()
            }
        parseBoundedJson(plugins)
            .jsonObject.getValue("result").jsonObject.let { result ->
                require(result.getValue("type").jsonPrimitive.string() == "plugin_list") { "Unexpected plugin response" }
                result.getValue("plugins").jsonArray.forEach { item ->
                    val plugin = item.jsonObject
                    require(PLUGIN_ID.matches(plugin.getValue("plugin_id").jsonPrimitive.string())) { "Invalid plugin id" }
                    require(plugin.getValue("version").jsonPrimitive.string().isNotBlank()) { "Invalid plugin version" }
                    plugin.getValue("plugin_root").jsonPrimitive.string()
                    plugin.getValue("enabled").jsonPrimitive.strictBoolean()
                    plugin["warnings"]?.jsonArray?.forEach { it.jsonPrimitive.string() }
                }
            }
        val architecture = normalizeArchitecture(os, rawArchitecture.trim())
        catalog.targetFor(os, architecture)
        val normalizedHome = normalizeHome(os, home)
        bootstrap = BootstrapSnapshot(
            herdrPath,
            os,
            architecture,
            normalizedHome,
            plugins,
            optionalSha256(fields[5]),
            optionalSha256(fields[6]),
        )
        return DiscoveryResult.Ready(herdrPath, os, architecture)
    }

    suspend fun preview(
        routeName: String,
        os: RemoteOperatingSystem,
        architecture: String,
    ): BridgeInstallPlan {
        val target = catalog.targetFor(os, architecture)
        val artifact = catalog.artifactFor(os, architecture)
        val root = expectedRoot(os, target)
        return BridgeInstallPlan(
            BridgeApproval(
                routeName, os, architecture, target,
                root,
                catalog.pluginVersion, catalog.minHerdrVersion, BridgeArtifactCatalog.sha256(artifact.binary()),
                os.toWire(),
            ),
            this,
        )
    }

    internal suspend fun preview(routeName: String, osName: String, architecture: String) =
        preview(routeName, remoteOperatingSystemFromWire(osName), architecture)

    suspend fun install(plan: BridgeInstallPlan, herdrPath: String): VerifiedInstall {
        val preview = approved(plan)
        val expectedTarget = catalog.targetFor(preview.os, preview.architecture)
        val expectedRoot = expectedRoot(preview.os, expectedTarget)
        require(
            preview.target == expectedTarget && preview.root == expectedRoot &&
                preview.bridgeVersion == catalog.pluginVersion && preview.minimumHerdrVersion == catalog.minHerdrVersion,
        ) { "Forged bridge preview" }
        val previous = verifiedRollbackCandidate(preview, herdrPath)
        val binaryName = if (preview.os == RemoteOperatingSystem.WINDOWS) "herdroid-bridge.exe" else "herdroid-bridge"
        val manifestPath = "${preview.root}/herdr-plugin.toml"
        val binaryPath = "${preview.root}/bin/$binaryName"
        val artifact = catalog.artifactFor(preview.os, preview.architecture)
        val expectedSha = catalog.expectedShaFor(preview.os, preview.architecture)
        require(preview.sha256 == expectedSha && BridgeArtifactCatalog.sha256(artifact.binary()) == expectedSha) { "Bridge preview is no longer trusted" }
        var linkAttempted = false
        try {
            command(RemoteCommands.makeDirectory(preview.os, "${preview.root}/bin"))
            transport.upload(manifestPath, artifact.manifest())
            transport.upload(binaryPath, artifact.binary())
            if (preview.os == RemoteOperatingSystem.LINUX) transport.chmod(binaryPath, 0b111000000)
            check(remoteSha256(binaryPath, preview.os) == expectedSha) { "Bridge readback hash mismatch" }
            check(catalog.hasTrustedManifest(transport.read(manifestPath, MAX_MANIFEST_BYTES))) { "Manifest readback mismatch" }
            linkAttempted = true
            command(RemoteCommands.herdr(preview.os, herdrPath, "plugin", "link", manifestPath))
            val installed = stockBridgePlugin(herdrPath, preview.os)
            val verifiedRoot = installed?.root?.let(::normalizePluginRoot)
            require(
                installed != null &&
                    installed.enabled &&
                    installed.version == catalog.pluginVersion &&
                    pathKey(preview.os, installed.root) == pathKey(preview.os, expectedRoot) &&
                    pathKey(preview.os, installed.manifestPath) == pathKey(preview.os, manifestPath),
            ) {
                "Bridge plugin root was not installed"
            }
            return VerifiedBridgeInstall(
                this,
                preview.target,
                preview.os,
                preview.architecture,
                checkNotNull(verifiedRoot),
                herdrPath,
            )
        } catch (failure: Throwable) {
            if (linkAttempted && previous != null) {
                val rollbackFailure = try {
                    withContext(NonCancellable) { restore(previous, herdrPath, preview.os) }
                    null
                } catch (rollback: Throwable) {
                    rollback
                }
                if (rollbackFailure != null) {
                    throw IllegalStateException(
                        "Bridge installation failed and rollback failed",
                        failure,
                    ).also { it.addSuppressed(rollbackFailure) }
                }
            }
            throw failure
        }
    }

    suspend fun verifyExisting(plan: BridgeInstallPlan, herdrPath: String): VerifiedInstall? {
        val preview = approved(plan)
        val target = catalog.targetFor(preview.os, preview.architecture)
        val root = expectedRoot(preview.os, target)
        require(
            preview.target == target && preview.root == root && preview.bridgeVersion == catalog.pluginVersion &&
                preview.minimumHerdrVersion == catalog.minHerdrVersion && preview.sha256 == catalog.expectedShaFor(preview.os, preview.architecture),
        ) { "Forged bridge preview" }
        val snapshot = bootstrap?.takeIf {
            it.os == preview.os && it.architecture == preview.architecture &&
                pathKey(preview.os, it.herdrPath) == pathKey(preview.os, herdrPath)
        }
        val installed = if (snapshot == null) {
            stockBridgePlugin(herdrPath, preview.os)
        } else {
            stockBridgePlugin(snapshot.plugins)
        } ?: return null
        val manifest = normalizePluginRoot(installed.manifestPath)
        val installedRoot = normalizePluginRoot(installed.root)
        val binary = "$installedRoot/bin/" +
            if (preview.os == RemoteOperatingSystem.WINDOWS) "herdroid-bridge.exe" else "herdroid-bridge"
        if (
            !installed.enabled || installed.version != catalog.pluginVersion ||
            pathKey(preview.os, installedRoot) != pathKey(preview.os, root) ||
            pathKey(preview.os, manifest) != pathKey(preview.os, "$root/herdr-plugin.toml")
        ) return null
        val trusted = if (snapshot != null) {
            val artifact = catalog.artifactFor(preview.os, preview.architecture)
            snapshot.manifestSha == BridgeArtifactCatalog.sha256(artifact.manifest()) &&
                snapshot.binarySha == catalog.expectedShaFor(preview.os, preview.architecture)
        } else {
            coroutineScope {
                listOf(
                    async { catalog.hasTrustedManifest(transport.read(manifest, MAX_MANIFEST_BYTES)) },
                    async { hasTrustedBinary(binary, preview.os, preview.architecture) },
                ).awaitAll().all { it }
            }
        }
        if (!trusted) return null
        return VerifiedBridgeInstall(this, preview.target, preview.os, preview.architecture, installedRoot, herdrPath)
    }

    fun launchDescriptor(install: VerifiedInstall): BridgeLaunchDescriptor {
        val verified = install as? VerifiedBridgeInstall
            ?: throw IllegalArgumentException("Unrecognized bridge installation")
        require(verified.owner === this) { "Bridge installation belongs to another installer" }
        return BridgeLaunchDescriptor(
            verified.target,
            verified.os,
            verified.architecture,
            verified.herdrPath,
            "${verified.root}/bin/" +
                if (verified.os == RemoteOperatingSystem.WINDOWS) "herdroid-bridge.exe" else "herdroid-bridge",
        )
    }

    fun cachedLaunchDescriptor(target: String, herdrPath: String, bridgePath: String): BridgeLaunchDescriptor? {
        if (catalog.entries.none { it.target == target }) return null
        val (os, architecture) = when (target) {
            BridgeArtifactCatalog.LINUX_X64 -> RemoteOperatingSystem.LINUX to "x86_64"
            BridgeArtifactCatalog.LINUX_ARM64 -> RemoteOperatingSystem.LINUX to "aarch64"
            BridgeArtifactCatalog.WINDOWS_X64 -> RemoteOperatingSystem.WINDOWS to "x86_64"
            else -> return null
        }
        if (!isAbsolute(os, herdrPath) || !isAbsolute(os, bridgePath)) return null
        return BridgeLaunchDescriptor(target, os, architecture, herdrPath, bridgePath)
    }

    fun launchCommand(install: VerifiedInstall): String = launchCommand(launchDescriptor(install))

    fun launchCommand(descriptor: BridgeLaunchDescriptor): String =
        RemoteCommands.bridge(descriptor.os, descriptor.bridgePath, descriptor.herdrPath)

    private fun approved(plan: BridgeInstallPlan): BridgeApproval {
        require(plan.owner === this) { "Bridge install plan belongs to another installer" }
        return plan.approval
    }

    fun parseBoundedJson(raw: String) = Json.parseToJsonElement(raw.also {
        require(it.toByteArray().size <= MAX_DISCOVERY_BYTES) { "Discovery output exceeds limit" }
    })

    private suspend fun stockBridgePlugin(herdrPath: String, os: RemoteOperatingSystem): StockPlugin? {
        return stockBridgePlugin(command(RemoteCommands.herdr(os, herdrPath, "plugin", "list", "--json")))
    }

    private fun stockBridgePlugin(raw: String): StockPlugin? {
        val plugins = parseBoundedJson(raw).jsonObject
            .getValue("result").jsonObject.let { result ->
                require(result.getValue("type").jsonPrimitive.string() == "plugin_list") { "Unexpected plugin response" }
                result.getValue("plugins").jsonArray
            }
            .filter { it.jsonObject.getValue("plugin_id").jsonPrimitive.string() == BridgeArtifactCatalog.PLUGIN_ID }
        val plugin = plugins.singleOrNull()?.jsonObject ?: return null
        return StockPlugin(
            version = plugin.getValue("version").jsonPrimitive.string(),
            manifestPath = plugin.getValue("manifest_path").jsonPrimitive.string(),
            root = plugin.getValue("plugin_root").jsonPrimitive.string(),
            enabled = plugin.getValue("enabled").jsonPrimitive.strictBoolean(),
        )
    }

    private suspend fun verifiedRollbackCandidate(
        preview: BridgeApproval,
        herdrPath: String,
    ): RollbackCandidate? {
        val plugin = stockBridgePlugin(herdrPath, preview.os) ?: return null
        val approvedTarget = catalog.targetFor(preview.os, preview.architecture)
        if (!plugin.enabled || preview.target != approvedTarget || !STABLE_VERSION.matches(plugin.version)) return null
        val previewKey = pathKey(preview.os, preview.root)
        val currentSuffix = "/${catalog.pluginVersion}/$approvedTarget"
        if (!isAbsolute(preview.os, normalizePluginRoot(preview.root)) || !previewKey.endsWith(currentSuffix)) return null
        val pluginBase = previewKey.removeSuffix(currentSuffix)
        if (!pluginBase.endsWith("/.herdroid/plugins/${BridgeArtifactCatalog.PLUGIN_ID}")) return null
        val expectedRoot = "$pluginBase/${plugin.version}/$approvedTarget"
        if (
            !isAbsolute(preview.os, normalizePluginRoot(plugin.root)) ||
            pathKey(preview.os, plugin.root) != expectedRoot ||
            pathKey(preview.os, plugin.manifestPath) != "$expectedRoot/herdr-plugin.toml"
        ) return null
        if (!hasTrustedManifestContract(transport.read(normalizePluginRoot(plugin.manifestPath), MAX_MANIFEST_BYTES), plugin.version)) return null
        if (plugin.version == catalog.pluginVersion) {
            val binaryName = if (preview.os == RemoteOperatingSystem.WINDOWS) "herdroid-bridge.exe" else "herdroid-bridge"
            if (!hasTrustedBinary("${normalizePluginRoot(plugin.root)}/bin/$binaryName", preview.os, preview.architecture)) return null
        }
        return RollbackCandidate(
            version = plugin.version,
            manifestPath = normalizePluginRoot(plugin.manifestPath),
            root = normalizePluginRoot(plugin.root),
        )
    }

    private suspend fun hasTrustedBinary(path: String, os: RemoteOperatingSystem, architecture: String): Boolean {
        return remoteSha256(path, os) == catalog.expectedShaFor(os, architecture)
    }

    private suspend fun remoteSha256(path: String, os: RemoteOperatingSystem): String {
        val hash = command(RemoteCommands.sha256(os, path)).trim()
        require(SHA256.matches(hash)) { "Invalid remote SHA-256" }
        return hash.lowercase()
    }

    private suspend fun restore(candidate: RollbackCandidate, herdrPath: String, os: RemoteOperatingSystem) {
        command(RemoteCommands.herdr(os, herdrPath, "plugin", "link", candidate.manifestPath))
        val restored = stockBridgePlugin(herdrPath, os)
        require(
            restored != null &&
                restored.enabled &&
                restored.version == candidate.version &&
                pathKey(os, restored.root) == pathKey(os, candidate.root) &&
                pathKey(os, restored.manifestPath) == pathKey(os, candidate.manifestPath),
        ) { "Bridge rollback verification failed" }
    }

    private fun hasTrustedManifestContract(bytes: ByteArray, version: String): Boolean {
        val lines = bytes.decodeToString().replace("\r\n", "\n").removeSuffix("\n").split('\n').toMutableList()
        val versionLine = "version = \"$version\""
        val versionLines = lines.indices.filter { lines[it] == versionLine }
        if (versionLines.size != 1) return false
        lines[versionLines.single()] = "version = \"${catalog.pluginVersion}\""
        return catalog.hasTrustedManifest(lines.joinToString("\n").encodeToByteArray())
    }

    private fun pathKey(os: RemoteOperatingSystem, path: String): String = when (os) {
        RemoteOperatingSystem.LINUX -> path.trimEnd('/')
        RemoteOperatingSystem.WINDOWS -> normalizePluginRoot(path).replace('\\', '/').trimEnd('/').lowercase()
    }

    private suspend fun command(value: String): String {
        return commandOutput(transport.exec(value))
    }

    private fun commandOutput(result: RemoteCommandResult): String {
        if (result.exitStatus != 0) throw RemoteCommandFailure()
        require(result.stdout.toByteArray().size <= MAX_DISCOVERY_BYTES) { "Discovery output exceeds limit" }
        return result.stdout
    }

    private suspend fun lookupHerdr(os: RemoteOperatingSystem): String? {
        val result = transport.exec(RemoteCommands.resolveHerdr(os))
        require(result.stdout.toByteArray().size <= MAX_DISCOVERY_BYTES) { "Discovery output exceeds limit" }
        require(result.exitStatus != null) { "Remote command failed" }
        if (result.exitStatus != 0) {
            require(result.stdout.isBlank()) { "Remote command failed" }
            return null
        }
        val path = result.stdout.trim()
        require(isAbsolute(os, path)) { "Resolved Herdr path is not absolute" }
        return normalizeAbsolutePath(os, path)
    }

    private fun isAbsolute(os: RemoteOperatingSystem, path: String) = when (os) {
        RemoteOperatingSystem.LINUX -> path.startsWith('/')
        RemoteOperatingSystem.WINDOWS -> Regex("[A-Za-z]:[\\\\/].*").matches(path) || path.startsWith("\\\\") ||
            path.startsWith("\\\\?\\")
    }

    private fun normalizeAbsolutePath(os: RemoteOperatingSystem, path: String) =
        if (os == RemoteOperatingSystem.WINDOWS) normalizePluginRoot(path) else path

    private suspend fun expectedRoot(os: RemoteOperatingSystem, target: String): String {
        val home = bootstrap?.takeIf { it.os == os && catalog.targetFor(os, it.architecture) == target }?.home
            ?: normalizeHome(os, command(RemoteCommands.home(os)))
        return home.trimEnd('/', '\\') +
            "/.herdroid/plugins/${BridgeArtifactCatalog.PLUGIN_ID}/${catalog.pluginVersion}/$target"
    }

    private fun normalizeHome(os: RemoteOperatingSystem, raw: String): String {
        val home = raw.trim()
        require(isAbsolute(os, home)) { "Remote home is not absolute" }
        return normalizeAbsolutePath(os, home)
    }

    private fun optionalSha256(raw: String): String? = raw.takeIf(String::isNotEmpty)?.also {
        require(SHA256.matches(it)) { "Invalid remote SHA-256" }
    }?.lowercase()

    private fun parseHerdrVersion(raw: String) {
        val version = Regex("^herdr\\s+(${STABLE_VERSION.pattern})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?\\s*$")
            .matchEntire(raw.trim())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid Herdr version")
        val parts = version.split('.').map(String::toInt)
        require(parts[0] > 0 || parts[1] > 8 || parts[1] == 8 && parts[2] >= 0) { "Herdr version is too old" }
    }

    private fun normalizeArchitecture(os: RemoteOperatingSystem, raw: String): String = when (os) {
        RemoteOperatingSystem.LINUX -> when (raw) {
            "x86_64", "aarch64" -> raw
            else -> throw IllegalArgumentException("Unsupported architecture")
        }
        RemoteOperatingSystem.WINDOWS -> {
            val parts = raw.split('|')
            require(parts.size == 2 && parts[0] == "windows") { "Unsupported architecture" }
            when (parts[1]) {
                "AMD64", "x86_64" -> "x86_64"
                "ARM64", "aarch64" -> "aarch64"
                else -> throw IllegalArgumentException("Unsupported architecture")
            }
        }
    }

    companion object {
        const val MAX_DISCOVERY_BYTES = MAX_BRIDGE_OUTPUT_BYTES
        const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val BOOTSTRAP_FIELDS = 7
        private val PLUGIN_ID = Regex("[A-Za-z0-9:._-]{1,120}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val STABLE_VERSION = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")

        fun normalizePluginRoot(root: String): String = when {
            root.startsWith("\\\\?\\UNC\\") -> "\\\\" + root.removePrefix("\\\\?\\UNC\\")
            root.startsWith("\\\\?\\") -> root.removePrefix("\\\\?\\")
            else -> root
        }
    }
}

private fun JsonPrimitive.string() = content.also { require(isString) { "Expected string" } }

private fun JsonPrimitive.strictBoolean() = boolean.also { require(!isString) { "Expected boolean" } }
