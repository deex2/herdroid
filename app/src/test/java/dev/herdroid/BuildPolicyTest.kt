package dev.herdroid

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BuildPolicyTest {
    @Test
    fun release_signing_is_explicit_secret_driven_and_versioned() {
        val build = repository.resolve("app/build.gradle.kts").readText()

        assertTrue(build.contains("versionCode = 1"))
        assertTrue(build.contains("versionName = \"0.1.0\""))
        assertTrue(build.contains("HERDROID_SIGNING_STORE_FILE"))
        assertTrue(build.contains("HERDROID_SIGNING_PASSWORD"))
        assertTrue(build.contains("keyAlias = \"herdroid-release\""))
        assertTrue(build.contains("signingConfig = signingConfigs.findByName(\"release\")"))
    }

    @Test
    fun tagged_releases_are_signed_verified_and_published_as_prereleases() {
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val readme = repository.resolve("README.md").readText()

        assertTrue(workflow.contains("TAG_NAME: $" + "{{ github.ref_name }}"))
        assertFalse(workflow.contains("\"$" + "{{ github.ref_name }}\""))
        assertEquals(1, Regex("""contents:\s+write""").findAll(workflow).count())
        assertTrue(workflow.substringAfter("\n  release:").contains("contents: write"))
        assertEquals(1, Regex("""secrets\.HERDROID_SIGNING_KEY_BASE64""").findAll(workflow).count())
        assertEquals(2, Regex("""secrets\.HERDROID_SIGNING_PASSWORD""").findAll(workflow).count())
        assertTrue(workflow.contains("gh release create"))
        assertTrue(workflow.contains("--prerelease"))
        assertTrue(workflow.contains("--verify-tag"))
        assertTrue(workflow.contains("--latest=false"))
        assertTrue(workflow.contains("b0c9df1f4688879f830d3e41811ff76486eb5c00e8998ab1cde8b338ad131770"))
        assertTrue(workflow.contains("'certificate SHA-256 digest:\\s*([0-9a-fA-F]{64})'"))
        assertTrue(
            workflow.indexOf("name: Remove release key") in
                0 until workflow.indexOf("name: Verify signed release"),
        )
        assertTrue(
            readme.contains(
                "B0:C9:DF:1F:46:88:87:9F:83:0D:3E:41:81:1F:F7:64:" +
                    "86:EB:5C:00:E8:99:8A:B1:CD:E8:B3:38:AD:13:17:70",
            ),
        )
    }

    @Test
    fun manifest_enforces_app_and_service_policy() {
        val manifest = repository.resolve("app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertEquals(1, Regex("<service\\b").findAll(manifest).count())
        assertTrue(manifest.contains("android:name=\"dev.herdroid.session.impl.ConnectionService\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertFalse(manifest.contains("BOOT_COMPLETED"))
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    @Test
    fun main_activity_uses_safe_drawing_insets_instead_of_status_bar_only_padding() {
        val activity = repository.resolve("app/src/main/java/dev/herdroid/MainActivity.kt").readText()

        assertTrue(activity.contains("safeDrawingPadding()"))
        assertFalse(activity.contains("statusBarsPadding()"))
    }

    @Test
    fun android_12_backup_rules_exclude_every_domain_from_cloud_and_transfer() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))

        val rulesFile = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue(rulesFile.isFile)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = document.getElementsByTagName(sectionName).item(0) as Element
            val exclusionsByDomain = (0 until section.childNodes.length)
                .mapNotNull { section.childNodes.item(it) as? Element }
                .filter { it.tagName == "exclude" }
                .groupBy({ it.getAttribute("domain") }, { it.getAttribute("path") })
            assertEquals(
                setOf(
                    "root",
                    "file",
                    "database",
                    "sharedpref",
                    "external",
                    "device_root",
                    "device_file",
                    "device_database",
                    "device_sharedpref",
                ),
                exclusionsByDomain.keys,
            )
            exclusionsByDomain.values.forEach { assertEquals(listOf("."), it) }
        }
    }

    @Test
    fun third_party_notices_are_packaged_and_published_with_bridge_artifacts() {
        val buildFile = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/ci.yml").readText()
        val verifier = File("../scripts/verify-bridge-apk.ps1").readText()

        assertTrue(File("../THIRD_PARTY_NOTICES.md").isFile)
        assertTrue(buildFile.contains("THIRD_PARTY_NOTICES.md"))
        assertTrue(verifier.contains("assets/THIRD_PARTY_NOTICES.md"))
        assertTrue(workflow.contains("THIRD_PARTY_NOTICES.md"))
    }

    @Test
    fun ci_uses_the_approved_toolchains_and_full_sha_actions() {
        val workflow = File("../.github/workflows/ci.yml").readText()
        assertTrue(
            workflow.replace(Regex("""\s+"""), " ").contains(
                "clean testDebugUnitTest test lintDebug lint assembleDebug assembleDebugAndroidTest assembleRelease",
            ),
        )
        val expected = mapOf(
            "actions/checkout" to "3d3c42e5aac5ba805825da76410c181273ba90b1",
            "actions/setup-java" to "b6effb05e454b25005698d916606bdc6ffcbf961",
            "actions/upload-artifact" to "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
            "actions/download-artifact" to "3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c",
            "dtolnay/rust-toolchain" to "4360b52568e2003a75bf9bc1d59f33a8e3fc893c",
        )
        expected.forEach { (action, sha) -> assertTrue(workflow.contains("$action@$sha")) }
        assertTrue(workflow.contains("toolchain: 1.97.1"))
        assertTrue(workflow.contains("java-version: '17.0.20+8'"))
        Regex("uses:\\s+[^@\\s]+@([^\\s#]+)").findAll(workflow).forEach {
            assertTrue(it.groupValues[1].matches(Regex("[0-9a-f]{40}")))
        }
        assertTrue(workflow.replace('\\', '/').contains("build/downloaded-bridge-artifacts/bridge-\$target/build/bridge-artifacts/\$target/bin/herdroid-bridge\$suffix"))
    }

    private val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
        it.parentFile
    }.first { it.resolve("settings.gradle.kts").isFile }
}
