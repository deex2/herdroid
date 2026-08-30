package dev.herdroid.session.impl

import dev.herdroid.core.herdr.BridgeArtifactCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeCatalogLoaderTest {
    @Test
    fun packagedAssetsWaitForTheInjectedIoDispatcher() = runTest {
        val reads = mutableListOf<String>()
        val files = trustedCatalogFiles()
        val io = StandardTestDispatcher(testScheduler)
        val loader = BridgeCatalogLoader(
            readAsset = { path -> reads += path; files.getValue(path) },
            ioDispatcher = io,
        )

        val loading = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { loader.load() }

        assertTrue(reads.isEmpty())
        testScheduler.runCurrent()
        loading.await()
        assertEquals(files.keys, reads.toSet())
    }

    private fun trustedCatalogFiles(): Map<String, ByteArray> {
        val manifest = """id = "dev.herdroid.bridge"
name = "Herdroid Bridge"
version = "0.1.0"
min_herdr_version = "0.8.0"
description = "SSH-stdio companion for the Herdroid Android client"
platforms = ["linux", "windows"]""".encodeToByteArray()
        val targets = listOf(
            "x86_64-unknown-linux-gnu" to "herdroid-bridge",
            "aarch64-unknown-linux-gnu" to "herdroid-bridge",
            "x86_64-pc-windows-msvc" to "herdroid-bridge.exe",
        )
        val files = linkedMapOf<String, ByteArray>()
        val entries = targets.joinToString(",") { (target, executable) ->
            val binaryPath = "$target/bin/$executable"
            val manifestPath = "$target/herdr-plugin.toml"
            val binary = "binary-$target".encodeToByteArray()
            files["bridge/$binaryPath"] = binary
            files["bridge/$manifestPath"] = manifest
            """{"target":"$target","sha256":"${BridgeArtifactCatalog.sha256(binary)}","binary":"$binaryPath","manifest":"$manifestPath"}"""
        }
        val catalog = """{"plugin_id":"dev.herdroid.bridge","plugin_version":"0.1.0","min_herdr_version":"0.8.0","protocol":1,"targets":[$entries]}"""
            .encodeToByteArray()
        return linkedMapOf("bridge/catalog.json" to catalog).apply { putAll(files) }
    }
}
