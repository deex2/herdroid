package dev.herdroid.core.herdr

import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.ssh.BridgeTransport
import dev.herdroid.core.ssh.RemoteCommandResult
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeInstallerTest {
    @Test
    fun `remote commands quote POSIX arguments and encode PowerShell as UTF16LE`() {
        assertEquals("'a b' 'it'\"'\"'s'", RemoteCommands.posix("a b", "it's"))

        val command = "& 'C:\\Program Files\\Herdr\\herdr.exe' --version"
        val encoded = RemoteCommands.powerShell(command)
        val payload = encoded.substringAfterLast(' ')
        assertEquals(command, String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_16LE))
        assertTrue(encoded.startsWith("powershell -NoProfile -NonInteractive -EncodedCommand "))

        val makeDirectory = RemoteCommands.makeDirectory(RemoteOperatingSystem.WINDOWS, "C:\\Users\\a'b")
        assertEquals(
            "[IO.Directory]::CreateDirectory('C:\\Users\\a''b') | Out-Null",
            String(java.util.Base64.getDecoder().decode(makeDirectory.substringAfterLast(' ')), Charsets.UTF_16LE),
        )

        assertEquals(
            "hash=\$('sha256sum' '--' '/tmp/a b') || exit; set -- \$hash; printf %s \"\$1\"",
            RemoteCommands.sha256(RemoteOperatingSystem.LINUX, "/tmp/a b"),
        )
        val windowsHash = RemoteCommands.sha256(RemoteOperatingSystem.WINDOWS, "C:\\Users\\a'b\\bridge.exe")
        assertEquals(
            "[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash(" +
                "[IO.File]::ReadAllBytes('C:\\Users\\a''b\\bridge.exe'))).Replace('-','').ToLowerInvariant()",
            String(java.util.Base64.getDecoder().decode(windowsHash.substringAfterLast(' ')), Charsets.UTF_16LE),
        )
    }

    @Test
    fun `catalog rejects wrong pins duplicate traversal and incomplete release`() {
        val good = "bridge".encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(pluginVersion = "0.1.1"), mode = CatalogMode.DEVELOPMENT) }
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(minHerdrVersion = "0.8.1"), mode = CatalogMode.DEVELOPMENT) }
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(protocol = 2), mode = CatalogMode.DEVELOPMENT) }
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(binary = "x86_64-unknown-linux-gnu/bin/../bridge"), mode = CatalogMode.DEVELOPMENT) }
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(targets = "$linuxTarget,$linuxTarget"), mode = CatalogMode.DEVELOPMENT) }
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(sha256(good)), mode = CatalogMode.RELEASE) }
        assertThrows(IllegalArgumentException::class.java) { BridgeArtifactCatalog.parse(catalogJson(empty = true), mode = CatalogMode.RELEASE) }
    }

    @Test
    fun `catalog rejects self hashed binary and mutation hook manifest`() {
        val good = "trusted bridge".encodeToByteArray()
        val selfHashedEvil = BridgeArtifact("evil".encodeToByteArray(), validManifest.encodeToByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            BridgeArtifactCatalog.parse(catalogJson(sha256(good)), mapOf(linuxTarget to selfHashedEvil), CatalogMode.DEVELOPMENT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeArtifactCatalog.parse(
                catalogJson(sha256(good)),
                mapOf(linuxTarget to BridgeArtifact(good, "$validManifest\n[[actions]]".encodeToByteArray())),
                CatalogMode.DEVELOPMENT,
            )
        }
    }

    @Test
    fun `catalog snapshots input and artifact accessors return copies`() = runBlocking {
        val binary = "bridge".encodeToByteArray()
        val manifest = validManifest.encodeToByteArray()
        val catalog = BridgeArtifactCatalog.parse(
            catalogJson(sha256(binary)),
            mapOf(linuxTarget to BridgeArtifact(binary, manifest)),
            CatalogMode.DEVELOPMENT,
        )
        binary[0] = 'x'.code.toByte()
        manifest[0] = 'x'.code.toByte()
        val exposed = catalog.artifactFor(RemoteOperatingSystem.LINUX, "x86_64")
        exposed.binary()[0] = 'x'.code.toByte()
        exposed.manifest()[0] = 'x'.code.toByte()

        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "{\"id\":\"plugin-list\",\"result\":{\"type\":\"plugin_list\",\"plugins\":[]}}"),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, pluginList(pluginEntry(priorRoot("0.1.0"), "0.1.0"))),
            ),
        )
        val installer = BridgeInstaller(remote, catalog)
        val preview = installer.preview("route", "linux", "x86_64")
        val verified = installer.install(preview, "/usr/bin/herdr")

        assertEquals("bridge", remote.uploads.getValue("${preview.approval.root}/bin/herdroid-bridge").decodeToString())
        assertEquals(validManifest, remote.uploads.getValue("${preview.approval.root}/herdr-plugin.toml").decodeToString())
        assertEquals("'/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/x86_64-unknown-linux-gnu/bin/herdroid-bridge' '--stdio' '--herdr-bin' '/usr/bin/herdr'", installer.launchCommand(verified))
    }

    @Test
    fun `catalog accepts only LF or CRLF manifest formatting`() {
        val binary = "bridge".encodeToByteArray()
        BridgeArtifactCatalog.parse(
            catalogJson(sha256(binary)),
            mapOf(linuxTarget to BridgeArtifact(binary, (validManifest.replace("\n", "\r\n") + "\r\n").encodeToByteArray())),
            CatalogMode.DEVELOPMENT,
        )
        assertThrows(IllegalArgumentException::class.java) {
            BridgeArtifactCatalog.parse(
                catalogJson(sha256(binary)),
                mapOf(linuxTarget to BridgeArtifact(binary, "$validManifest\nextra = \"no\"".encodeToByteArray())),
                CatalogMode.DEVELOPMENT,
            )
        }
    }

    @Test
    fun `fixed discovery parses advisory fields without exposing them`() = runBlocking {
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/usr/local/bin/herdr\n"),
                RemoteCommandResult(
                    0,
                    bootstrapOutput(
                        plugins = "{\"id\":\"plugin-list\",\"result\":{\"type\":\"plugin_list\",\"plugins\":[{\"plugin_id\":\"dev.herdroid.bridge\",\"name\":\"Herdroid Bridge\",\"version\":\"0.1.0\",\"min_herdr_version\":\"0.8.0\",\"manifest_path\":\"/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/x86_64-unknown-linux-gnu/herdr-plugin.toml\",\"plugin_root\":\"/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/x86_64-unknown-linux-gnu\",\"enabled\":false,\"warnings\":[\"old\"]}]}}",
                        sessions = "{\"sessions\":[{\"name\":\"work\",\"default\":false,\"running\":true,\"socket_path\":\"/tmp/work.sock\",\"session_dir\":\"/tmp/work\"}]}",
                    ),
                ),
            ),
        )
        val installer = BridgeInstaller(remote, trustedCatalog())

        val discovery = installer.discover(RemoteOperatingSystem.LINUX)

        assertEquals("/usr/local/bin/herdr", (discovery as DiscoveryResult.Ready).herdrPath)
        assertThrows(IllegalArgumentException::class.java) { installer.parseBoundedJson("x".repeat(BridgeInstaller.MAX_DISCOVERY_BYTES + 1)) }
        Unit
    }

    @Test
    fun `one bootstrap command discovers and verifies the installed bridge`() = runBlocking {
        val root = priorRoot("0.1.0")
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/usr/bin/herdr\n"),
                RemoteCommandResult(
                    0,
                    listOf(
                        "/home/a",
                        "x86_64",
                        "herdr 0.8.1",
                        "{\"sessions\":[]}",
                        pluginList(pluginEntry(root, "0.1.0")),
                        sha256(validManifest.encodeToByteArray()),
                        sha256("bridge".encodeToByteArray()),
                    ).joinToString("\u0000"),
                ),
            ),
        )
        val installer = BridgeInstaller(remote, trustedCatalog())

        val discovery = installer.discover(RemoteOperatingSystem.LINUX) as DiscoveryResult.Ready
        val plan = installer.preview("route", discovery.os, discovery.architecture)
        val verified = installer.verifyExisting(plan, discovery.herdrPath)

        assertTrue(verified != null)
        assertEquals(2, remote.commands.size)
        assertTrue(remote.readPaths.isEmpty())
    }

    @Test
    fun `bootstrap response requires exact framing and hashes`() = runBlocking {
        listOf(
            bootstrapOutput().substringBeforeLast('\u0000'),
            bootstrapOutput(binarySha = "short"),
        ).forEach { output ->
            expectFailure<IllegalArgumentException> {
                BridgeInstaller(
                    RecordingTransport(listOf(RemoteCommandResult(0, "/usr/bin/herdr\n"), RemoteCommandResult(0, output))),
                    trustedCatalog(),
                ).discover(RemoteOperatingSystem.LINUX)
            }
        }
    }

    @Test
    fun `discovery validates discarded field types without narrowing plugin versions`() = runBlocking {
        val sessions = """{"sessions":[{"name":"work","running":true,"socket_path":"/tmp/work.sock"}]}"""
        val plugins = pluginList(
            """{"plugin_id":"dev.herdroid.bridge","version":"nightly","plugin_root":"/tmp/plugin","enabled":true,"warnings":["old"]}""",
        )
        discoverLinux("x86_64", "herdr 0.8.0\n", trustedCatalog(), sessions, plugins)
        listOf(
            sessions.replace("\"work\"", "\"bad/name\"") to plugins,
            sessions.replace("\"running\":true", "\"running\":\"true\"") to plugins,
            sessions.replace("\"/tmp/work.sock\"", "1") to plugins,
            sessions to plugins.replace("dev.herdroid.bridge", "bad/plugin"),
            sessions to plugins.replace("\"nightly\"", "\"   \""),
            sessions to plugins.replace("\"nightly\"", "1"),
            sessions to plugins.replace("\"/tmp/plugin\"", "false"),
            sessions to plugins.replace("\"enabled\":true", "\"enabled\":\"true\""),
            sessions to plugins.replace("[\"old\"]", "[1]"),
            sessions to plugins.replace("plugin_list", "unexpected"),
        ).forEach { (sessionOutput, pluginOutput) ->
            expectFailure<IllegalArgumentException> {
                discoverLinux("x86_64", "herdr 0.8.0\n", trustedCatalog(), sessionOutput, pluginOutput)
            }
        }
    }

    @Test
    fun `automatic discovery falls back to encoded Windows probe and accepts extended UNC path`() = runBlocking {
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(1, ""),
                RemoteCommandResult(0, "\\\\?\\UNC\\server\\share\\herdr.exe\n"),
                RemoteCommandResult(0, bootstrapOutput(home = "C:\\Users\\a", architecture = "windows|AMD64")),
            ),
        )

        val discovery = BridgeInstaller(remote, trustedCatalog()).discover()

        assertEquals(RemoteOperatingSystem.WINDOWS, (discovery as DiscoveryResult.Ready).os)
        assertEquals("x86_64", discovery.architecture)
        assertTrue(remote.commands[1].startsWith("powershell -NoProfile -NonInteractive -EncodedCommand "))
    }

    @Test
    fun `explicit Windows drive path accepts forward slashes`() = runBlocking {
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, bootstrapOutput(home = "C:\\Users\\a", architecture = "windows|AMD64")),
            ),
        )

        val discovery = BridgeInstaller(remote, trustedCatalog())
            .discover("C:/Users/a/herdr.exe") as DiscoveryResult.Ready

        assertEquals(RemoteOperatingSystem.WINDOWS, discovery.os)
        assertEquals("C:/Users/a/herdr.exe", discovery.herdrPath)
    }

    @Test
    fun `preview resolves the versioned root against remote home`() = runBlocking {
        val preview = BridgeInstaller(
            RecordingTransport(listOf(RemoteCommandResult(0, "/home/a\n"))),
            trustedCatalog(),
        ).preview("route", "linux", "x86_64")

        assertEquals("/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/x86_64-unknown-linux-gnu", preview.approval.root)
    }

    @Test
    fun `only a clean missing lookup asks the user for a Herdr path`() = runBlocking {
        assertEquals(
            DiscoveryResult.ExplicitPathRequired,
            BridgeInstaller(RecordingTransport(listOf(RemoteCommandResult(1, ""))), trustedCatalog())
                .discover(RemoteOperatingSystem.LINUX),
        )
        listOf(
            RemoteCommandResult(null, "/usr/bin/herdr\n"),
            RemoteCommandResult(1, "/usr/bin/herdr\n"),
            RemoteCommandResult(0, "relative/herdr\n"),
        ).forEach { result ->
            expectFailure<IllegalArgumentException> {
                BridgeInstaller(RecordingTransport(listOf(result)), trustedCatalog())
                    .discover(RemoteOperatingSystem.LINUX)
            }
        }
    }

    @Test
    fun `discovery and install require exact successful command statuses`() = runBlocking {
        val successfulDiscovery = listOf(
            RemoteCommandResult(0, "/usr/bin/herdr\n"),
            RemoteCommandResult(0, bootstrapOutput()),
        )
        listOf("lookup", "bootstrap").forEachIndexed { index, name ->
            val results = successfulDiscovery.toMutableList()
            results[index] = results[index].copy(exitStatus = null)
            val failure = expectFailure<IllegalArgumentException> {
                BridgeInstaller(RecordingTransport(results), trustedCatalog()).discover(RemoteOperatingSystem.LINUX)
            }
            assertTrue("$name must fail closed", failure.message.orEmpty().contains("Remote command failed"))
        }

        val remote = RecordingTransport(listOf(RemoteCommandResult(0, "/home/a\n"), RemoteCommandResult(null, "/home/a\n")))
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")
        expectFailure<IllegalArgumentException> { installer.install(preview, "/usr/bin/herdr") }
        assertTrue(remote.uploads.isEmpty())
    }

    @Test
    fun `platform and Herdr version formats are canonical and strict`() = runBlocking {
        listOf("x86_64" to "x86_64", "aarch64" to "aarch64").forEach { (raw, expected) ->
            val catalog = if (expected == "x86_64") trustedCatalog() else armLinuxCatalog()
            assertEquals(expected, discoverLinux(raw, "herdr 0.8.0\n", catalog).architecture)
        }
        discoverLinux("x86_64", "herdr 0.8.0-preview.2026-08-04-d78e3d3b5126\n", trustedCatalog())
        listOf("x86_64;whoami", "amd64", "arm64").forEach { raw ->
            expectFailure<IllegalArgumentException> { discoverLinux(raw, "herdr 0.8.0\n", trustedCatalog()) }
        }
        listOf("windows|AMD64", "windows|x86_64").forEach { raw ->
            assertEquals("x86_64", discoverWindows(raw).architecture)
        }
        listOf("Windows|AMD64", "windows|AMD64|oops", "windows|ARM64", "linux|x86_64").forEach { raw ->
            expectFailure<IllegalArgumentException> { discoverWindows(raw) }
        }
        listOf("herdr 0.7.9\n", "herdr 0.8.0-\n", "herdr 00.8.0\n", "herdr 0.08.0\n", "herdr 0.8.00\n").forEach { version ->
            expectFailure<IllegalArgumentException> { discoverLinux("x86_64", version, trustedCatalog()) }
        }
    }

    @Test
    fun `install plan cannot cross installer ownership`() = runBlocking {
        val remote = RecordingTransport(listOf(RemoteCommandResult(0, "/home/a\n"), RemoteCommandResult(0, "/home/a\n")))
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")
        expectFailure<IllegalArgumentException> {
            BridgeInstaller(remote, trustedCatalog()).install(preview, "/usr/bin/herdr")
        }
        assertTrue(remote.uploads.isEmpty())
        assertEquals(1, remote.commands.size)
    }

    @Test
    fun `verified install cannot launch through another installer`() = runBlocking {
        val root = "/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$linuxTarget"
        val remote = installTransport("/home/a", root)
        val installer = BridgeInstaller(remote, trustedCatalog())
        val verified = installer.install(installer.preview("route", "linux", "x86_64"), "/usr/bin/herdr")
        val otherRemote = RecordingTransport(emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            BridgeInstaller(otherRemote, trustedCatalog()).launchCommand(verified)
        }

        assertTrue(otherRemote.commands.isEmpty())
    }

    @Test
    fun `launch uses the Herdr path bound during verification`() = runBlocking {
        val root = "/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$linuxTarget"
        val remote = installTransport("/home/a", root)
        val installer = BridgeInstaller(remote, trustedCatalog())
        val verified = installer.install(installer.preview("route", "linux", "x86_64"), "/usr/bin/herdr")

        assertEquals(
            RemoteCommands.bridge(RemoteOperatingSystem.LINUX, "$root/bin/herdroid-bridge", "/usr/bin/herdr"),
            installer.launchCommand(verified),
        )
        assertEquals(
            BridgeLaunchDescriptor(
                linuxTarget,
                RemoteOperatingSystem.LINUX,
                "x86_64",
                "/usr/bin/herdr",
                "$root/bin/herdroid-bridge",
            ),
            installer.launchDescriptor(verified),
        )
        assertEquals(
            installer.launchDescriptor(verified),
            installer.cachedLaunchDescriptor(linuxTarget, "/usr/bin/herdr", "$root/bin/herdroid-bridge"),
        )
        assertNull(installer.cachedLaunchDescriptor("unknown", "/usr/bin/herdr", "/tmp/bridge"))
        assertNull(installer.cachedLaunchDescriptor(linuxTarget, "relative/herdr", "/tmp/bridge"))
    }

    @Test
    fun `compatible stock bridge verifies its hash without downloading the binary`() = runBlocking {
        val root = "/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$linuxTarget"
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(root, "0.1.0"))),
            ),
        )
        remote.files["$root/herdr-plugin.toml"] = validManifest.encodeToByteArray()
        remote.files["$root/bin/herdroid-bridge"] = "bridge".encodeToByteArray()
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")

        val verified = installer.verifyExisting(preview, "/usr/bin/herdr")

        assertTrue(verified != null)
        assertEquals("'/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/x86_64-unknown-linux-gnu/bin/herdroid-bridge' '--stdio' '--herdr-bin' '/usr/bin/herdr'", installer.launchCommand(requireNotNull(verified)))
        assertTrue(remote.uploads.isEmpty())
        assertFalse(remote.commands.any(::isPluginLink))
        assertEquals(listOf("$root/herdr-plugin.toml"), remote.readPaths)
    }

    @Test
    fun `remote binary hash must be exactly sha256`() = runBlocking {
        val root = priorRoot("0.1.0")
        listOf("short", "g".repeat(64), "0".repeat(64) + " extra").forEach { malformed ->
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(pluginEntry(root, "0.1.0"))),
                ),
            ).apply {
                files["$root/herdr-plugin.toml"] = validManifest.encodeToByteArray()
                files["$root/bin/herdroid-bridge"] = "bridge".encodeToByteArray()
                hashResult = RemoteCommandResult(0, malformed)
            }
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            expectFailure<IllegalArgumentException> { installer.verifyExisting(preview, "/usr/bin/herdr") }
        }
    }

    @Test
    fun `stock bridge rejects loosely typed plugin fields`() = runBlocking {
        val root = "/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$linuxTarget"
        val valid = pluginEntry(root, "0.1.0")
        listOf(
            valid.replace("\"plugin_id\":\"dev.herdroid.bridge\"", "\"plugin_id\":7"),
            valid.replace("\"version\":\"0.1.0\"", "\"version\":1"),
            valid.replace("\"manifest_path\":\"$root/herdr-plugin.toml\"", "\"manifest_path\":7"),
            valid.replace("\"plugin_root\":\"$root\"", "\"plugin_root\":false"),
            valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
        ).forEach { malformed ->
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(malformed)),
                ),
            )
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            expectFailure<IllegalArgumentException> { installer.verifyExisting(preview, "/usr/bin/herdr") }
        }
    }

    @Test
    fun `successful installs launch exact normalized Linux and Windows bridge binaries`() = runBlocking {
        val linuxRoot = "/home/a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$linuxTarget"
        assertEquals(
            RemoteCommands.bridge(RemoteOperatingSystem.LINUX, "$linuxRoot/bin/herdroid-bridge", "/usr/bin/herdr"),
            installAndLaunch(RemoteOperatingSystem.LINUX, "/home/a", "/usr/bin/herdr", trustedCatalog(), linuxRoot),
        )

        val linuxRemote = installTransport("/home/a", linuxRoot)
        val linuxInstaller = BridgeInstaller(linuxRemote, trustedCatalog())
        linuxInstaller.install(linuxInstaller.preview("route", "linux", "x86_64"), "/usr/bin/herdr")
        assertEquals(listOf("$linuxRoot/bin/herdroid-bridge" to 0b111000000), linuxRemote.modes)

        listOf(
            "C:\\Users\\a" to "C:\\Users\\a",
            "\\\\?\\C:\\Users\\a" to "C:\\Users\\a",
            "\\\\server\\share\\a" to "\\\\server\\share\\a",
            "\\\\?\\UNC\\server\\share\\a" to "\\\\server\\share\\a",
        ).forEach { (remoteHome, normalizedHome) ->
            val root = "$normalizedHome/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$windowsTarget"
            assertEquals(
                RemoteCommands.bridge(RemoteOperatingSystem.WINDOWS, "$root/bin/herdroid-bridge.exe", "C:\\Herdr\\herdr.exe"),
                installAndLaunch(RemoteOperatingSystem.WINDOWS, remoteHome, "C:\\Herdr\\herdr.exe", windowsCatalog(), root),
            )
        }

        val windowsRoot = "C:\\Users\\a/.herdroid/plugins/dev.herdroid.bridge/0.1.0/$windowsTarget"
        val windowsRemote = installTransport("C:\\Users\\a", windowsRoot)
        val windowsInstaller = BridgeInstaller(windowsRemote, windowsCatalog())
        windowsInstaller.install(windowsInstaller.preview("route", "windows", "x86_64"), "C:\\Herdr\\herdr.exe")
        assertTrue(windowsRemote.modes.isEmpty())
    }

    @Test
    fun `uncertain link failure restores the verified predecessor`() = runBlocking {
        val prior = priorRoot("0.0.9")
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(prior, "0.0.9"))),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, pluginList(pluginEntry(prior, "0.0.9"))),
            ),
        )
        remote.files["$prior/herdr-plugin.toml"] = manifest("0.0.9")
        remote.failAtCommand[6] = IOException("link response lost")
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")

        expectFailure<IOException> { installer.install(preview, "/usr/bin/herdr") }

        assertEquals(2, remote.commands.count(::isPluginLink))
        assertTrue(remote.commands.last(::isPluginLink).contains("$prior/herdr-plugin.toml"))
    }

    @Test
    fun `remote read limits reject oversized manifest`() = runBlocking {
        val root = priorRoot("0.1.0")
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(root, "0.1.0"))),
            ),
        )
        remote.files["$root/herdr-plugin.toml"] = ByteArray(BridgeInstaller.MAX_MANIFEST_BYTES + 1)
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")

        expectFailure<IllegalArgumentException> { installer.verifyExisting(preview, "/usr/bin/herdr") }
        Unit
    }

    @Test
    fun `mismatched existing binary hash triggers replacement`() = runBlocking {
        val root = priorRoot("0.1.0")
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(root, "0.1.0"))),
            ),
        )
        remote.files["$root/herdr-plugin.toml"] = validManifest.encodeToByteArray()
        remote.files["$root/bin/herdroid-bridge"] = "older bridge".encodeToByteArray()
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")

        assertNull(installer.verifyExisting(preview, "/usr/bin/herdr"))
    }

    @Test
    fun `manifest read and binary hash run concurrently`() = runTest {
        val root = priorRoot("0.1.0")
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(root, "0.1.0"))),
            ),
        ).apply {
            files["$root/herdr-plugin.toml"] = validManifest.encodeToByteArray()
            files["$root/bin/herdroid-bridge"] = "bridge".encodeToByteArray()
            blockCommandRange = 4..4
            blockReadCount = 1
        }
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")

        val verification = async { installer.verifyExisting(preview, "/usr/bin/herdr") }
        val concurrent = withTimeoutOrNull(100) {
            remote.commandsBlocked.await()
            remote.readsBlocked.await()
            true
        } ?: false
        remote.allowCommands.complete(Unit)
        remote.allowReads.complete(Unit)

        requireNotNull(verification.await())
        assertTrue("verification reads ran sequentially", concurrent)
    }

    @Test
    fun `null plugin status fails before install and during post-link or rollback verification`() = runBlocking {
        val candidateRemote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(null, pluginList()),
            ),
        )
        val candidateInstaller = BridgeInstaller(candidateRemote, trustedCatalog())
        val candidatePreview = candidateInstaller.preview("route", "linux", "x86_64")
        expectFailure<IllegalArgumentException> { candidateInstaller.install(candidatePreview, "/usr/bin/herdr") }
        assertTrue(candidateRemote.uploads.isEmpty())

        val prior = priorRoot("0.0.9")
        val rollbackRemote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(prior, "0.0.9"))),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(null, pluginList()),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, pluginList(pluginEntry(prior, "0.0.9"))),
            ),
        )
        rollbackRemote.files["$prior/herdr-plugin.toml"] = manifest("0.0.9")
        val rollbackInstaller = BridgeInstaller(rollbackRemote, trustedCatalog())
        val rollbackPreview = rollbackInstaller.preview("route", "linux", "x86_64")
        expectFailure<IllegalArgumentException> { rollbackInstaller.install(rollbackPreview, "/usr/bin/herdr") }
        assertEquals(2, rollbackRemote.commands.count(::isPluginLink))
    }

    @Test
    fun `post-link verification failures restore a verified prior plugin`() = runBlocking {
        val priorRoot = priorRoot("0.0.9")
        val failures = listOf(
            RemoteCommandResult(0, pluginList(pluginEntry(root = "/wrong", version = "0.1.0"))),
            RemoteCommandResult(7, ""),
            RemoteCommandResult(0, "not-json"),
        )

        failures.forEach { postLinkResult ->
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(pluginEntry(priorRoot, "0.0.9"))),
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(0, ""),
                    postLinkResult,
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(0, pluginList(pluginEntry(priorRoot, "0.0.9"))),
                ),
            )
            remote.files["$priorRoot/herdr-plugin.toml"] = manifest("0.0.9")
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            val failure = expectFailure<Throwable> { installer.install(preview, "/usr/bin/herdr") }

            assertFalse(failure.message.orEmpty().contains("rollback", ignoreCase = true))
            assertEquals(2, remote.commands.count(::isPluginLink))
            assertTrue(remote.commands.last(::isPluginLink).contains("$priorRoot/herdr-plugin.toml"))
        }
    }

    @Test
    fun `cancellation after committed link restores prior plugin non-cancellably`() = runBlocking {
        val priorRoot = priorRoot("0.0.9")
        val remote = RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, "/home/a\n"),
                RemoteCommandResult(0, pluginList(pluginEntry(priorRoot, "0.0.9"))),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, pluginList(pluginEntry(priorRoot, "0.0.9"))),
            ),
        )
        remote.files["$priorRoot/herdr-plugin.toml"] = manifest("0.0.9")
        remote.blockAtCommand = 7
        val installer = BridgeInstaller(remote, trustedCatalog())
        val preview = installer.preview("route", "linux", "x86_64")

        val install = launch { installer.install(preview, "/usr/bin/herdr") }
        remote.commandBlocked.await()
        install.cancel()
        install.join()

        assertTrue(install.isCancelled)
        assertEquals(2, remote.commands.count(::isPluginLink))
        assertTrue(remote.commands.last(::isPluginLink).contains("$priorRoot/herdr-plugin.toml"))
        assertTrue(remote.commands.last().contains("'plugin' 'list'"))
    }

    @Test
    fun `rollback command and verification failures preserve the install failure`() = runBlocking {
        val priorRoot = priorRoot("0.0.9")
        listOf(
            listOf(RemoteCommandResult(9, "")),
            listOf(RemoteCommandResult(null, "")),
            listOf(
                RemoteCommandResult(0, ""),
                RemoteCommandResult(0, pluginList(pluginEntry("/wrong", "0.0.9"))),
            ),
            listOf(
                RemoteCommandResult(0, ""),
                RemoteCommandResult(null, pluginList(pluginEntry(priorRoot, "0.0.9"))),
            ),
        ).forEach { rollbackResults ->
            val original = IllegalStateException("original post-link failure")
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(pluginEntry(priorRoot, "0.0.9"))),
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(0, ""),
                ) + rollbackResults,
            )
            remote.files["$priorRoot/herdr-plugin.toml"] = manifest("0.0.9")
            remote.failAtCommand[6] = original
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            val failure = expectFailure<IllegalStateException> { installer.install(preview, "/usr/bin/herdr") }

            assertEquals("Bridge installation failed and rollback failed", failure.message)
            assertTrue(failure.cause === original)
            assertEquals(1, failure.suppressed.size)
            assertEquals(2, remote.commands.count(::isPluginLink))
        }
    }

    @Test
    fun `unverified prior entries are never rollback candidates`() = runBlocking {
        val validRoot = priorRoot("0.0.9")
        val currentRoot = priorRoot("0.1.0")
        val cases = listOf(
            pluginEntry(validRoot, "0.0.9", enabled = false) to emptyMap(),
            pluginEntry(priorRoot("0.0.9-alpha"), "0.0.9-alpha") to emptyMap(),
            pluginEntry("relative/root", "0.0.9") to emptyMap(),
            pluginEntry("/tmp/dev.herdroid.bridge/0.0.9/$linuxTarget", "0.0.9") to emptyMap(),
            pluginEntry(priorRoot("0.0.9", "wrong-target"), "0.0.9") to emptyMap(),
            pluginEntry(validRoot, "0.0.9") to mapOf("$validRoot/herdr-plugin.toml" to "untrusted".encodeToByteArray()),
            pluginEntry(currentRoot, "0.1.0") to mapOf(
                "$currentRoot/herdr-plugin.toml" to manifest("0.1.0"),
                "$currentRoot/bin/herdroid-bridge" to "older bridge".encodeToByteArray(),
            ),
        )

        cases.forEach { (entry, files) ->
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(entry)),
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(0, pluginList(pluginEntry("/wrong", "0.1.0"))),
                ),
            )
            remote.files.putAll(files)
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            expectFailure<Throwable> { installer.install(preview, "/usr/bin/herdr") }

            assertEquals(1, remote.commands.count(::isPluginLink))
        }
    }

    @Test
    fun `indeterminate predecessor verification aborts before remote mutation`() = runBlocking {
        listOf(
            "0.0.9" to "herdr-plugin.toml",
            "0.1.0" to "bin/herdroid-bridge",
        ).forEach { (version, failingSuffix) ->
            val root = priorRoot(version)
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(pluginEntry(root, version))),
                ),
            )
            remote.files["$root/herdr-plugin.toml"] = manifest(version)
            remote.files["$root/bin/herdroid-bridge"] = "bridge".encodeToByteArray()
            if (failingSuffix == "herdr-plugin.toml") {
                remote.failReadPath = "$root/$failingSuffix"
            } else {
                remote.failHashPath = "$root/$failingSuffix"
            }
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            expectFailure<IOException> { installer.install(preview, "/usr/bin/herdr") }

            assertTrue(remote.uploads.isEmpty())
            assertTrue(remote.modes.isEmpty())
            assertFalse(remote.commands.any { it.contains("mkdir") || isPluginLink(it) })
        }
    }

    @Test
    fun `pre-link errors do not rollback while failed links are treated as uncertain`() = runBlocking {
        val priorRoot = priorRoot("0.1.0")
        listOf(true to 0, false to 9, false to null).forEach { (failReadback, linkStatus) ->
            val remote = RecordingTransport(
                listOf(
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, "/home/a\n"),
                    RemoteCommandResult(0, pluginList(pluginEntry(priorRoot, "0.1.0"))),
                    RemoteCommandResult(0, ""),
                    RemoteCommandResult(linkStatus, ""),
                ),
            )
            remote.files["$priorRoot/herdr-plugin.toml"] = manifest("0.1.0")
            remote.files["$priorRoot/bin/herdroid-bridge"] = "bridge".encodeToByteArray()
            if (failReadback) remote.mutateUploadPath = "/bin/herdroid-bridge"
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            expectFailure<Throwable> { installer.install(preview, "/usr/bin/herdr") }

            val links = remote.commands.filter(::isPluginLink)
            assertEquals(if (failReadback) 0 else 2, links.size)
        }
    }

    @Test
    fun `upload mutation fails readback validation before linking`() = runBlocking {
        listOf(
            "/bin/herdroid-bridge" to "Bridge readback hash mismatch",
            "/herdr-plugin.toml" to "Manifest readback mismatch",
        ).forEach { (path, message) ->
            val remote = RecordingTransport(listOf(RemoteCommandResult(0, "/home/a\n"), RemoteCommandResult(0, "/home/a\n"), RemoteCommandResult(0, pluginList())))
            remote.mutateUploadPath = path
            val installer = BridgeInstaller(remote, trustedCatalog())
            val preview = installer.preview("route", "linux", "x86_64")

            val failure = expectFailure<IllegalStateException> { installer.install(preview, "/usr/bin/herdr") }

            assertEquals(message, failure.message)
            assertFalse(remote.commands.any { it.contains("plugin link") && it.contains(preview.approval.root) })
        }
    }

    @Test
    fun `normalizes Windows plugin roots and rejects unsafe identifiers`() {
        assertEquals("C:\\Users\\a\\plugin", BridgeInstaller.normalizePluginRoot("\\\\?\\C:\\Users\\a\\plugin"))
        assertEquals("\\\\server\\share\\plugin", BridgeInstaller.normalizePluginRoot("\\\\?\\UNC\\server\\share\\plugin"))
        assertTrue(BridgeIdentifiers.validSession("space.1"))
        assertFalse(BridgeIdentifiers.validSession("bad/name"))
        assertTrue(BridgeIdentifiers.validPane("ws:pane-1"))
        assertFalse(BridgeIdentifiers.validPane("pane;rm"))
    }

    private fun trustedCatalog(binary: ByteArray = "bridge".encodeToByteArray()) = BridgeArtifactCatalog.parse(
        catalogJson(sha256(binary)),
        mapOf(linuxTarget to BridgeArtifact(binary, validManifest.encodeToByteArray())),
        CatalogMode.DEVELOPMENT,
    )

    private fun armLinuxCatalog(): BridgeArtifactCatalog {
        val binary = "bridge".encodeToByteArray()
        val target = BridgeArtifactCatalog.LINUX_ARM64
        return BridgeArtifactCatalog.parse(
            catalogJson(sha256(binary), binary = "$target/bin/herdroid-bridge", targets = target),
            mapOf(target to BridgeArtifact(binary, validManifest.encodeToByteArray())),
            CatalogMode.DEVELOPMENT,
        )
    }

    private fun windowsCatalog(): BridgeArtifactCatalog {
        val binary = "bridge".encodeToByteArray()
        return BridgeArtifactCatalog.parse(
            catalogJson(sha256(binary), binary = "$windowsTarget/bin/herdroid-bridge.exe", targets = windowsTarget),
            mapOf(windowsTarget to BridgeArtifact(binary, validManifest.encodeToByteArray())),
            CatalogMode.DEVELOPMENT,
        )
    }

    private suspend fun installAndLaunch(
        os: RemoteOperatingSystem,
        home: String,
        herdrPath: String,
        catalog: BridgeArtifactCatalog,
        installedRoot: String,
    ): String {
        val remote = installTransport(home, installedRoot)
        val installer = BridgeInstaller(remote, catalog)
        val preview = installer.preview("route", os, "x86_64")
        return installer.launchCommand(installer.install(preview, herdrPath))
    }

    private fun installTransport(home: String, installedRoot: String) = RecordingTransport(
        listOf(
            RemoteCommandResult(0, "$home\n"),
            RemoteCommandResult(0, "$home\n"),
            RemoteCommandResult(0, pluginList()),
            RemoteCommandResult(0, ""),
            RemoteCommandResult(0, ""),
            RemoteCommandResult(0, pluginList(pluginEntry(installedRoot, "0.1.0"))),
        ),
    )

    private suspend fun discoverLinux(
        architecture: String,
        version: String,
        catalog: BridgeArtifactCatalog,
        sessions: String = """{"sessions":[]}""",
        plugins: String = pluginList(),
    ): DiscoveryResult.Ready = BridgeInstaller(
        RecordingTransport(
            listOf(
                RemoteCommandResult(0, "/usr/bin/herdr\n"),
                RemoteCommandResult(0, bootstrapOutput(architecture = architecture, version = version, sessions = sessions, plugins = plugins)),
            ),
        ),
        catalog,
    ).discover(RemoteOperatingSystem.LINUX) as DiscoveryResult.Ready

    private suspend fun discoverWindows(architecture: String): DiscoveryResult.Ready = BridgeInstaller(
        RecordingTransport(
            listOf(
                RemoteCommandResult(0, "C:\\Program Files\\Herdr\\herdr.exe\n"),
                RemoteCommandResult(0, bootstrapOutput(home = "C:\\Users\\a", architecture = architecture)),
            ),
        ),
        trustedCatalog(),
    ).discover(RemoteOperatingSystem.WINDOWS) as DiscoveryResult.Ready

    private fun catalogJson(
        hash: String = "00",
        pluginVersion: String = "0.1.0",
        minHerdrVersion: String = "0.8.0",
        protocol: Int = 1,
        binary: String = "$linuxTarget/bin/herdroid-bridge",
        targets: String = linuxTarget,
        empty: Boolean = false,
    ): String {
        val entries = if (empty) "[]" else targets.split(',').joinToString(prefix = "[", postfix = "]") { target ->
            "{\"target\":\"$target\",\"sha256\":\"$hash\",\"binary\":\"$binary\",\"manifest\":\"$target/herdr-plugin.toml\"}"
        }
        return "{\"plugin_id\":\"dev.herdroid.bridge\",\"plugin_version\":\"$pluginVersion\",\"min_herdr_version\":\"$minHerdrVersion\",\"protocol\":$protocol,\"targets\":$entries}"
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun isPluginLink(command: String) = command.contains("'plugin' 'link'")

    private fun priorRoot(version: String, target: String = linuxTarget) =
        "/home/a/.herdroid/plugins/dev.herdroid.bridge/$version/$target"

    private fun manifest(version: String) = validManifest
        .replace("version = \"0.1.0\"", "version = \"$version\"")
        .encodeToByteArray()

    private fun pluginList(vararg plugins: String) =
        "{\"id\":\"plugin-list\",\"result\":{\"type\":\"plugin_list\",\"plugins\":[${plugins.joinToString()}]}}"

    private fun bootstrapOutput(
        home: String = "/home/a",
        architecture: String = "x86_64",
        version: String = "herdr 0.8.0",
        sessions: String = "{\"sessions\":[]}",
        plugins: String = pluginList(),
        manifestSha: String = "",
        binarySha: String = "",
    ) = listOf(home, architecture, version.trim(), sessions, plugins, manifestSha, binarySha).joinToString("\u0000")

    private fun pluginEntry(root: String, version: String, enabled: Boolean = true): String {
        val jsonRoot = root.replace("\\", "\\\\")
        return """{
        "plugin_id":"dev.herdroid.bridge",
        "name":"Herdroid Bridge",
        "version":"$version",
        "min_herdr_version":"0.8.0",
        "description":"SSH-stdio companion for the Herdroid Android client",
        "manifest_path":"$jsonRoot/herdr-plugin.toml",
        "plugin_root":"$jsonRoot",
        "enabled":$enabled,
        "platforms":["linux","windows"],
        "source":{"kind":"local"}
    }""".replace("\n", "").replace("        ", "")
    }

    private class RecordingTransport(results: List<RemoteCommandResult>) : BridgeTransport {
        private val results = ArrayDeque(results)
        val commands = mutableListOf<String>()
        val uploads = linkedMapOf<String, ByteArray>()
        val files = linkedMapOf<String, ByteArray>()
        val modes = mutableListOf<Pair<String, Int>>()
        val readPaths = mutableListOf<String>()
        val failAtCommand = mutableMapOf<Int, Throwable>()
        var blockAtCommand: Int? = null
        val commandBlocked = CompletableDeferred<Unit>()
        var blockCommandRange: IntRange? = null
        val commandsBlocked = CompletableDeferred<Unit>()
        val allowCommands = CompletableDeferred<Unit>()
        var blockReadCount = 0
        val readsBlocked = CompletableDeferred<Unit>()
        val allowReads = CompletableDeferred<Unit>()
        private var readsStarted = 0
        var mutateUploadPath: String? = null
        var failReadPath: String? = null
        var failHashPath: String? = null
        var hashResult: RemoteCommandResult? = null

        override suspend fun exec(command: String): RemoteCommandResult {
            commands += command
            val hashedFile = (files + uploads).entries.singleOrNull { (path) ->
                RemoteOperatingSystem.entries.any { RemoteCommands.sha256(it, path) == command }
            }
            val result = if (hashedFile == null) {
                results.removeFirstOrNull() ?: RemoteCommandResult(0, "")
            } else {
                hashResult ?: RemoteCommandResult(
                    0,
                    MessageDigest.getInstance("SHA-256").digest(hashedFile.value).joinToString("") { "%02x".format(it) },
                )
            }
            if (commands.size == blockAtCommand) {
                commandBlocked.complete(Unit)
                awaitCancellation()
            }
            blockCommandRange?.takeIf { commands.size in it }?.let { range ->
                if (commands.size == range.last) commandsBlocked.complete(Unit)
                allowCommands.await()
            }
            failAtCommand[commands.size]?.let { throw it }
            if (failHashPath != null && hashedFile?.key == failHashPath) throw IOException("hash failed")
            return result
        }

        override suspend fun upload(path: String, bytes: ByteArray) {
            if (mutateUploadPath != null && path.endsWith(mutateUploadPath!!)) bytes[0] = 'x'.code.toByte()
            uploads[path] = bytes.copyOf()
        }

        override suspend fun chmod(path: String, mode: Int) {
            modes += path to mode
        }

        override suspend fun read(path: String, maxBytes: Int): ByteArray {
            readPaths += path
            if (path == failReadPath) throw IOException("read failed")
            if (blockReadCount > 0) {
                if (++readsStarted == blockReadCount) readsBlocked.complete(Unit)
                allowReads.await()
            }
            val bytes = uploads[path]?.copyOf() ?: files[path]?.copyOf() ?: error("Missing remote file $path")
            require(bytes.size <= maxBytes) { "Remote file exceeds limit" }
            return bytes
        }
    }

    private companion object {
        const val linuxTarget = "x86_64-unknown-linux-gnu"
        const val windowsTarget = "x86_64-pc-windows-msvc"
        const val validManifest = """id = "dev.herdroid.bridge"
name = "Herdroid Bridge"
version = "0.1.0"
min_herdr_version = "0.8.0"
description = "SSH-stdio companion for the Herdroid Android client"
platforms = ["linux", "windows"]"""
    }
}

internal suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
    val failure = runCatching { block() }.exceptionOrNull()
    if (failure is T) return failure
    throw AssertionError("Expected ${T::class.java.simpleName}, got $failure", failure)
}
