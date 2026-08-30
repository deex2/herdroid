package dev.herdroid.core.ssh

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshDependencyPolicyTest {
    @Test
    fun sshOwnsTransportAndPinnedCryptoDependencies() {
        val appBuild = repository.resolve("app/build.gradle.kts").readText()
        val catalog = repository.resolve("gradle/libs.versions.toml").readText()

        assertTrue(appBuild.contains("implementation(libs.sshj)"))
        assertTrue(catalog.contains("bouncycastle = \"1.84\""))
        listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach { module ->
            assertTrue(
                Regex(
                    """implementation\("org\.bouncycastle:$module"\)\s*\{\s*version\s*\{\s*strictly\(libs\.versions\.bouncycastle\.get\(\)\)\s*}\s*}""",
                ).containsMatchIn(appBuild),
            )
        }
    }

    @Test
    fun sshCredentialOwnersCannotBeShallowCopiedAndClearOwnedArrays() {
        val bytes = "secret".encodeToByteArray()
        val password = SshAuthenticationInput.Password(bytes)
        val authenticationCopy = password.copyForAuthentication()

        password.close()

        assertArrayEquals(ByteArray(bytes.size), bytes)
        assertArrayEquals("secret".encodeToByteArray(), authenticationCopy)
        assertFalse(SshAuthenticationInput.Password::class.java.hasGeneratedCopy())
        authenticationCopy.fill(0)

        val source = byteArrayOf(1, 2, 3)
        val hardware = SshAuthenticationInput.HardwareKey(7, "alias", source)
        source.fill(9)
        val publicCopy = hardware.publicKeyOpenSsh()
        hardware.close()

        assertArrayEquals(byteArrayOf(1, 2, 3), publicCopy)
        assertArrayEquals(ByteArray(3), hardware.publicKeyOpenSsh())
        assertFalse(SshAuthenticationInput.HardwareKey::class.java.hasGeneratedCopy())
        publicCopy.fill(0)
    }

    private val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
        it.parentFile
    }.first { it.resolve("settings.gradle.kts").isFile }

    private fun Class<*>.hasGeneratedCopy() = declaredMethods.any { it.name == "copy" || it.name.startsWith("copy\$") }
}
