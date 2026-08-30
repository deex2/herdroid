package dev.herdroid.ssh

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.herdroid.core.data.ConnectionAuthenticationInput
import dev.herdroid.core.data.ConnectionEndpointInput
import dev.herdroid.core.data.ConnectionRouteInput
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.EndpointAuthenticationInput
import dev.herdroid.core.data.EndpointWriteInput
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteWriteInput
import dev.herdroid.core.keyvault.DeleteKeyResult
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.model.SshKeyOrigin
import dev.herdroid.core.ssh.ConnectedRoute
import dev.herdroid.core.ssh.SshAuthenticationInput
import dev.herdroid.core.ssh.SshConnectionInput
import dev.herdroid.core.ssh.SshConnector
import dev.herdroid.core.ssh.SshEndpointInput
import dev.herdroid.feature.keys.readKeyDocument
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SshRouteDeviceTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var routeRepository: RouteRepository
    @Inject lateinit var connectionRoutes: ConnectionRouteRepository
    @Inject lateinit var keyVault: KeyVault
    @Inject lateinit var sshConnector: SshConnector

    @Before
    fun inject() = hilt.inject()

    @Test
    fun physicalDevicePolicyRejectsOfficialAndroidEmulator() {
        assertFalse(
            isPhysicalDevice(
                fingerprint = "google/sdk_gphone_x86_64/generic_x86_64:10/QSR1/test-keys",
                model = "Android SDK built for x86_64",
                product = "sdk_gphone_x86_64",
                hardware = "ranchu",
            ),
        )
    }

    @Test
    fun importedDocumentIsConsumedFromWipeableTestCacheFile() {
        val source = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "herdroid-import-${UUID.randomUUID()}.key",
        )
        val expected = "private-import-fixture".encodeToByteArray()
        source.writeBytes(expected)

        val document = readImportDocument(source.name)

        try {
            assertArrayEquals(expected, document)
            assertFalse(source.exists())
        } finally {
            expected.fill(0)
            document.fill(0)
            source.delete()
        }
    }

    @Test
    fun rejectedImportDocumentStillDeletesTestCacheFile() {
        val source = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "herdroid-import-${UUID.randomUUID()}.key",
        )
        val oversized = ByteArray(256 * 1024 + 1)
        source.writeBytes(oversized)
        oversized.fill(0)

        assertTrue(runCatching { readImportDocument(source.name) }.isFailure)
        assertFalse(source.exists())
    }

    @Test
    fun directLinuxWithPassword() = exercise("direct_linux")

    @Test
    fun generatedHardwareKeyAuthenticatesWindowsDirect() =
        exerciseHardware(
            "hardware_windows_direct",
            KeySource.GENERATED,
            KeyLayout.DIRECT,
            expectedTargetOs = "windows",
        )

    @Test
    fun generatedHardwareKeyAuthenticatesLinuxDirect() =
        exerciseHardware(
            "hardware_linux_direct",
            KeySource.GENERATED,
            KeyLayout.DIRECT,
            expectedTargetOs = "linux",
        )

    @Test
    fun generatedHardwareKeyAuthenticatesThroughLinuxJump() =
        exerciseHardware(
            "hardware_linux_jump",
            KeySource.GENERATED,
            KeyLayout.SHARED,
            expectedJumpOs = "linux",
        )

    @Test
    fun generatedHardwareKeysCanBeDistinctForTargetAndJump() =
        exerciseHardware("hardware_distinct_keys", KeySource.GENERATED, KeyLayout.DISTINCT)

    @Test
    fun importedHardwareKeyAuthenticatesDirect() =
        exerciseHardware("hardware_imported_key", KeySource.IMPORTED, KeyLayout.DIRECT)

    @Test
    fun savedHardwareKeyReconnectsWithoutAuthenticationInput() =
        exerciseHardware(
            "hardware_reconnect",
            KeySource.GENERATED,
            KeyLayout.DIRECT,
            reconnect = true,
        )

    private fun exerciseHardware(
        caseName: String,
        source: KeySource,
        layout: KeyLayout,
        expectedTargetOs: String? = null,
        expectedJumpOs: String? = null,
        reconnect: Boolean = false,
    ) = runBlocking {
        assumeTrue(
            "physical_$caseName requires a physical Android device; emulators are never accepted",
            isPhysicalDevice(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT, Build.HARDWARE),
        )
        val config = hardwareConfig(caseName)
        val targetJson = config.getJSONObject("target")
        val jumpJson = config.optJSONObject("jump")
        assumeTrue(
            "physical_$caseName requires provider_url, provider_token, and endpoint fixture_id values",
            config.optString("provider_url").isNotBlank() &&
                config.optString("provider_token").isNotBlank() &&
                targetJson.optString("fixture_id").isNotBlank() &&
                (jumpJson == null || jumpJson.optString("fixture_id").isNotBlank()),
        )
        expectedTargetOs?.let { assertEquals(it, targetJson.getString("os").lowercase()) }
        expectedJumpOs?.let { assertEquals(it, jumpJson!!.getString("os").lowercase()) }
        assertEquals(layout == KeyLayout.DIRECT, jumpJson == null)

        val savedKeys = mutableListOf<HardwareKeyMetadata>()
        val installed = linkedSetOf<Pair<String, String>>()
        val provider = FixtureProvider(config.getString("provider_url"), config.getString("provider_token"))
        var routeId = 0L
        var connected: ConnectedRoute? = null
        try {
            val targetKey = createHardwareKey(caseName, "target", source, config)
            savedKeys += targetKey
            assertEquals(
                if (source == KeySource.GENERATED) SshKeyOrigin.GENERATED else SshKeyOrigin.IMPORTED,
                targetKey.origin,
            )
            val jumpKey = when (layout) {
                KeyLayout.DIRECT -> null
                KeyLayout.SHARED -> targetKey
                KeyLayout.DISTINCT -> createHardwareKey(
                    caseName,
                    "jump",
                    KeySource.GENERATED,
                    config,
                ).also(savedKeys::add)
            }
            assertTrue(targetKey.securityLevel in setOf(HardwareSecurityLevel.TEE, HardwareSecurityLevel.STRONGBOX))
            jumpKey?.let {
                assertTrue(it.securityLevel in setOf(HardwareSecurityLevel.TEE, HardwareSecurityLevel.STRONGBOX))
            }

            installPublicLine(provider, targetJson, targetKey, installed)
            if (jumpJson != null) installPublicLine(provider, jumpJson, requireNotNull(jumpKey), installed)
            val route = RouteWriteInput(
                id = 0,
                name = caseName,
                target = hardwareEndpoint(targetJson, targetKey),
                jump = jumpJson?.let { hardwareEndpoint(it, requireNotNull(jumpKey)) },
            )
            routeId = route.use { routeRepository.save(it) }
            val savedRoute = requireNotNull(routeRepository.findEditable(routeId))
            assertEquals(targetKey.id, savedRoute.target.keyId)
            jumpKey?.let {
                assertEquals(it.id, savedRoute.jump!!.keyId)
            }

            val targetHostKey = knownHost(config.getJSONObject("target_known_host"))
            val jumpHostKey = config.optJSONObject("jump_known_host")?.let(::knownHost)
            if (savedRoute.jump != null) assertNotEquals(targetHostKey.keyBase64, jumpHostKey!!.keyBase64)
            repeat(if (reconnect) 2 else 1) {
                val connection = connectionRoutes.loadForConnection(routeId).use { reloadedRoute ->
                    sshConnector.connect(
                        reloadedRoute.toSshInput(),
                        listOf(targetHostKey),
                        listOfNotNull(jumpHostKey),
                    )
                }
                connected = connection
                connection.use { assertProbe(it, targetJson.getString("os")) }
                connected = null
            }
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            cleanupFailures.capture { connected?.close() }
            installed.toList().asReversed().forEach { (fixture, line) ->
                cleanupFailures.capture { provider.remove(fixture, line) }
            }
            cleanupFailures.capture { if (routeId != 0L) routeRepository.delete(routeId) }
            savedKeys.asReversed().forEach { key ->
                cleanupFailures.capture {
                    check(keyVault.delete(key.id) == DeleteKeyResult.Deleted)
                }
            }
            if (cleanupFailures.isNotEmpty()) throw AssertionError(
                "Physical SSH fixture cleanup failed",
                cleanupFailures.first(),
            ).also { failure -> cleanupFailures.drop(1).forEach(failure::addSuppressed) }
        }
    }

    private suspend fun createHardwareKey(
        caseName: String,
        role: String,
        source: KeySource,
        config: JSONObject,
    ): HardwareKeyMetadata {
        val name = "$caseName-$role-${UUID.randomUUID()}"
        return try {
            when (source) {
                KeySource.GENERATED -> keyVault.generate(name)
                KeySource.IMPORTED -> keyVault.importKey(
                    name,
                    readImportDocument(config.getString("import_private_key_file")),
                    null,
                )
            }
        } catch (failure: IllegalArgumentException) {
            if (failure.message == "Hardware-backed key storage is unavailable on this device.") {
                assumeNoException("physical_$caseName requires TEE or StrongBox key storage", failure)
            }
            throw failure
        }
    }

    private fun installPublicLine(
        provider: FixtureProvider,
        endpoint: JSONObject,
        key: HardwareKeyMetadata,
        installed: MutableSet<Pair<String, String>>,
    ) {
        val fixture = endpoint.getString("fixture_id")
        val line = key.authorizedKeyLine
        if (installed.add(fixture to line)) provider.install(fixture, line)
    }

    private fun hardwareConfig(caseName: String): JSONObject {
        val encoded = InstrumentationRegistry.getArguments().getString("physical_$caseName")
        assumeNotNull(
            "Pass -e physical_$caseName <base64-json> on an approved physical device with an explicit fixture provider",
            encoded,
        )
        val decoded = Base64.getDecoder().decode(encoded)
        return try {
            JSONObject(decoded.toString(Charsets.UTF_8))
        } finally {
            decoded.fill(0)
        }
    }

    private fun hardwareEndpoint(json: JSONObject, key: HardwareKeyMetadata) = EndpointWriteInput(
        hostname = json.getString("hostname"),
        port = json.getInt("port"),
        username = json.getString("username"),
        authentication = EndpointAuthenticationInput.HardwareKey(key.id),
        herdrPath = null,
    )

    private fun exercise(caseName: String) = runBlocking {
        assumeTrue(
            "physical_$caseName requires a physical Android device; emulators are never accepted",
            isPhysicalDevice(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT, Build.HARDWARE),
        )
        val encoded = InstrumentationRegistry.getArguments().getString("physical_$caseName")
        assumeNotNull(
            "Pass -e physical_$caseName <base64-json> on the physical SSH test device",
            encoded,
        )
        val decoded = Base64.getDecoder().decode(encoded)
        val config = try {
            JSONObject(decoded.toString(Charsets.UTF_8))
        } finally {
            decoded.fill(0)
        }
        val target = endpoint(config.getJSONObject("target"))
        val jump = config.optJSONObject("jump")?.let(::endpoint)
        val targetHostKey = knownHost(config.getJSONObject("target_known_host"))
        val jumpHostKey = config.optJSONObject("jump_known_host")?.let(::knownHost)
        var routeId = 0L
        try {
            routeId = RouteWriteInput(0, "$caseName-${UUID.randomUUID()}", target, jump).use { route ->
                if (route.jump != null) assertNotEquals(targetHostKey.keyBase64, jumpHostKey!!.keyBase64)
                routeRepository.save(route)
            }
            connectionRoutes.loadForConnection(routeId).use { stored ->
                sshConnector.connect(
                    stored.toSshInput(),
                    listOf(targetHostKey),
                    listOfNotNull(jumpHostKey),
                ).use { route -> assertProbe(route, config.getJSONObject("target").getString("os")) }
            }
        } finally {
            if (routeId != 0L) routeRepository.delete(routeId)
        }
    }

    private suspend fun assertProbe(route: ConnectedRoute, targetOs: String) {
        val probe = fixedProbe(targetOs)
        val process = route.exec(probe.command)
        val output = kotlinx.coroutines.coroutineScope {
            async(Dispatchers.IO) { process.takeStdout().readBytes() }.also {
                assertEquals(0, process.awaitExit().status)
            }.await()
        }
        assertTrue(withContext(Dispatchers.Default) {
            output.toString(Charsets.UTF_8).contains(probe.expectedOutput)
        })
    }

    private fun readImportDocument(fileName: String): ByteArray {
        require(fileName.isNotBlank() && fileName == File(fileName).name) {
            "Import fixture must be a test-cache file name"
        }
        val cache = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir.canonicalFile
        val file = File(cache, fileName).canonicalFile
        require(file.parentFile == cache && file.isFile) { "Import fixture is unavailable" }
        var document: ByteArray? = null
        var bodyFailure: Throwable? = null
        try {
            document = file.inputStream().use(::readKeyDocument)
            return document
        } catch (failure: Throwable) {
            bodyFailure = failure
            document?.fill(0)
            throw failure
        } finally {
            try {
                wipeAndDelete(file)
            } catch (cleanupFailure: Throwable) {
                if (bodyFailure == null) {
                    document?.fill(0)
                    throw cleanupFailure
                }
                bodyFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun wipeAndDelete(file: File) {
        val zeroes = ByteArray(8 * 1024)
        RandomAccessFile(file, "rw").use { output ->
            var remaining = output.length()
            while (remaining > 0) {
                val count = minOf(zeroes.size.toLong(), remaining).toInt()
                output.write(zeroes, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        check(file.delete()) { "Import fixture cleanup failed" }
    }

    private fun isPhysicalDevice(
        fingerprint: String,
        model: String,
        product: String,
        hardware: String,
    ): Boolean {
        val normalizedFingerprint = fingerprint.lowercase()
        val normalizedModel = model.lowercase()
        val normalizedProduct = product.lowercase()
        val normalizedHardware = hardware.lowercase()
        return !(
            normalizedFingerprint.startsWith("generic") ||
                normalizedFingerprint.startsWith("unknown") ||
                "emulator" in normalizedFingerprint ||
                "emulator" in normalizedModel ||
                "android sdk built for" in normalizedModel ||
                normalizedProduct == "google_sdk" ||
                normalizedProduct.startsWith("sdk_") ||
                normalizedHardware in setOf("goldfish", "ranchu", "vbox86")
            )
    }

    private fun fixedProbe(targetOs: String): RouteProbe = when (targetOs) {
        "linux" -> RouteProbe("printf herdroid-ssh-probe", "herdroid-ssh-probe")
        "windows" -> RouteProbe("cmd.exe /d /c echo herdroid-ssh-probe", "herdroid-ssh-probe")
        else -> error("Target OS must be linux or windows")
    }

    private fun ConnectionRouteInput.toSshInput(): SshConnectionInput {
        val targetInput = target.toSshInput()
        var jumpInput: SshEndpointInput? = null
        return try {
            jumpInput = jump?.toSshInput()
            SshConnectionInput(routeName, targetInput, jumpInput)
        } catch (failure: Throwable) {
            jumpInput?.close()
            targetInput.close()
            throw failure
        }
    }

    private fun ConnectionEndpointInput.toSshInput(): SshEndpointInput {
        val connectorAuthentication = when (val source = authentication) {
            is ConnectionAuthenticationInput.Password ->
                SshAuthenticationInput.Password(source.moveToConnector())
            is ConnectionAuthenticationInput.HardwareKey -> {
                val publicKey = source.copyPublicKeyForConnection()
                try {
                    SshAuthenticationInput.HardwareKey(source.keyId, source.alias, publicKey)
                } finally {
                    publicKey.fill(0)
                }
            }
        }
        return try {
            SshEndpointInput(hostname, port, username, connectorAuthentication, herdrPath)
        } catch (failure: Throwable) {
            connectorAuthentication.close()
            throw failure
        }
    }

    private fun endpoint(json: JSONObject) = EndpointWriteInput(
        hostname = json.getString("hostname"),
        port = json.getInt("port"),
        username = json.getString("username"),
        authentication = when (json.getString("auth")) {
            "password" -> EndpointAuthenticationInput.Password(
                Base64.getDecoder().decode(json.getString("password_base64")),
            )
            else -> error("auth must be password")
        },
        herdrPath = null,
    )

    private fun knownHost(json: JSONObject) = KnownHostRecord(
        hostname = json.getString("hostname"),
        port = json.getInt("port"),
        algorithm = json.getString("algorithm"),
        keyBase64 = json.getString("key_base64"),
        acceptedAtEpochMillis = 1L,
    )

    private suspend fun MutableList<Throwable>.capture(block: suspend () -> Unit) {
        runCatching { block() }.exceptionOrNull()?.let(::add)
    }

    private enum class KeySource { GENERATED, IMPORTED }
    private enum class KeyLayout { DIRECT, SHARED, DISTINCT }
    private data class RouteProbe(val command: String, val expectedOutput: String)

    private class FixtureProvider(private val url: String, private val token: String) {
        init {
            require(URL(url).protocol == "https") { "Fixture provider must use HTTPS" }
        }

        fun install(fixture: String, authorizedKeyLine: String) =
            call("install", fixture, authorizedKeyLine)

        fun remove(fixture: String, authorizedKeyLine: String) =
            call("remove", fixture, authorizedKeyLine)

        private fun call(action: String, fixture: String, authorizedKeyLine: String) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("action", action)
                    .put("fixture_id", fixture)
                    .put("authorized_key_line", authorizedKeyLine)
                    .toString()
                    .encodeToByteArray()
                try {
                    connection.outputStream.use { it.write(body) }
                } finally {
                    body.fill(0)
                }
                val status = connection.responseCode
                (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                check(status in 200..299) { "Fixture provider $action failed with HTTP $status" }
            } finally {
                connection.disconnect()
            }
        }
    }
}
