package dev.herdroid.core.ssh.keys

import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.ssh.CreatedHardwareKey
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareSshKeyStoreTest {
    private val store = HardwareSshKeyStore()
    private val aliases = mutableListOf<String>()

    @After
    fun deleteTestAliases() {
        aliases.forEach(store::delete)
    }

    @Test
    fun generatedKeyIsHardwareBackedReloadableAndDeletableOrRefusedWithoutResidue() {
        val alias = newAlias()
        assertTrue(alias.startsWith("herdroid.ssh."))

        val result = runCatching { store.generate(alias) }

        result.onSuccess { generated ->
            generated.use {
                val publicKey = generated.publicKeyOpenSsh()
                try {
                    assertCreated(generated, alias, publicKey)
                    store.load(alias).use { assertMaterial(it, alias, publicKey) }
                } finally {
                    publicKey.fill(0)
                }
            }
            store.delete(alias)
            assertFalse(androidKeyStore().containsAlias(alias))
            assertThrows(Exception::class.java) { store.load(alias) }
        }.onFailure {
            assertEquals(HARDWARE_UNAVAILABLE, it.message)
            assertFalse(androidKeyStore().containsAlias(alias))
            assertThrows(Exception::class.java) { store.load(alias) }
        }
    }

    @Test
    fun importsEncryptedOpenSshP256AndClearsInputsOrRefusesWithoutResidue() {
        val alias = newAlias()
        val document = ENCRYPTED_OPENSSH_P256.encodeToByteArray()
        val passphrase = "test-passphrase".toCharArray()
        val expectedBlob = Base64.getDecoder().decode(
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBA8BRyh1vExpLAK2Hybju1pMf6FQbM4AaSe8Mxs3z6t3l+ugyqiY6+M+g6Xpy9sOJh4difVXpuMVSJ/x8PD+klI=",
        )

        val result = runCatching { store.importKey(alias, document, passphrase) }

        assertTrue(document.all { it == 0.toByte() })
        assertTrue(passphrase.all { it == '\u0000' })
        assertImportedOrUnavailable(alias, expectedBlob, result)
    }

    @Test
    fun importsPkcs8P256AndClearsInputOrRefusesWithoutResidue() {
        val alias = newAlias()
        val source = softwareKeyPair("secp256r1")
        val document = source.private.encoded.copyOf()
        val expectedBlob = SshPublicKeyCodec.encode(source.public as ECPublicKey)

        val result = runCatching { store.importKey(alias, document, null) }

        assertTrue(document.all { it == 0.toByte() })
        assertImportedOrUnavailable(alias, expectedBlob, result)
    }

    @Test
    fun importMismatchClosesLoadedMaterialAndClearsItsPublicBlob() {
        val alias = newAlias()
        val document = softwareKeyPair("secp256r1").private.encoded.copyOf()
        val mismatch = byteArrayOf(9, 8, 7)
        val imported = HardwareKeyMaterial(
            alias,
            softwareKeyPair("secp256r1"),
            mismatch,
            HardwareSecurityLevel.TEE,
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            store.importMaterial(alias, document, null) { imported }
        }

        assertEquals("Only ECDSA P-256 SSH keys are supported.", failure.message)
        assertArrayEquals(ByteArray(mismatch.size), imported.publicKeyOpenSsh())
        assertTrue(document.all { it == 0.toByte() })
        assertFalse(androidKeyStore().containsAlias(alias))
        mismatch.fill(0)
    }

    @Test
    fun signatureFailureClearsTheExactEncodedPublicBlob() {
        val alias = newAlias()
        createAndroidKey(alias)
        val encodedPublicBlob = byteArrayOf(6, 5, 4, 3)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            store.load(
                alias,
                securityLevelFor = { HardwareSecurityLevel.TEE },
                encodePublicKey = { encodedPublicBlob },
                signChallenge = { _, _ -> throw SignatureException("forced signature failure") },
            )
        }

        assertEquals(HARDWARE_UNAVAILABLE, failure.message)
        assertArrayEquals(ByteArray(encodedPublicBlob.size), encodedPublicBlob)
        assertFalse(androidKeyStore().containsAlias(alias))
    }

    @Test
    fun rejectsInvalidImportsAndClearsAllInputs() {
        val cases = listOf(
            softwareKeyPair("secp384r1").private.encoded to "Only ECDSA P-256 SSH keys are supported.",
            ByteArray(SshPublicKeyCodec.MAX_DOCUMENT_BYTES + 1) { 7 } to null,
            byteArrayOf(1, 2, 3, 4) to "Only ECDSA P-256 SSH keys are supported.",
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded to
                "Only ECDSA P-256 SSH keys are supported.",
        )

        cases.forEach { (document, expectedMessage) ->
            val alias = newAlias()
            val passphrase = "discard-me".toCharArray()

            val failure = assertThrows(IllegalArgumentException::class.java) {
                store.importKey(alias, document, passphrase)
            }

            expectedMessage?.let { assertEquals(it, failure.message) }
            assertTrue(document.all { it == 0.toByte() })
            assertTrue(passphrase.all { it == '\u0000' })
            assertFalse(androidKeyStore().containsAlias(alias))
        }
    }

    @Test
    fun invalidAliasStillClearsImportInputs() {
        val document = ByteArray(64) { 7 }
        val passphrase = "discard-me".toCharArray()

        assertThrows(IllegalArgumentException::class.java) {
            store.importKey("not-herdroid", document, passphrase)
        }

        assertTrue(document.all { it == 0.toByte() })
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun generateAndImportRejectExistingAliasesWithoutChangingThem() {
        val generateAlias = newAlias()
        createAndroidKey(generateAlias)
        val generatedBefore = certificateBytes(generateAlias)

        assertThrows(IllegalArgumentException::class.java) { store.generate(generateAlias) }

        assertArrayEquals(generatedBefore, certificateBytes(generateAlias))

        val importAlias = newAlias()
        createAndroidKey(importAlias)
        val importedBefore = certificateBytes(importAlias)
        val document = softwareKeyPair("secp256r1").private.encoded.copyOf()

        assertThrows(IllegalArgumentException::class.java) {
            store.importKey(importAlias, document, null)
        }

        assertTrue(document.all { it == 0.toByte() })
        assertArrayEquals(importedBefore, certificateBytes(importAlias))
    }

    @Test
    fun deleteRejectsUnrelatedAliasWithoutChangingIt() {
        val alias = "unrelated.${java.util.UUID.randomUUID()}"
        createAndroidKey(alias)
        val before = certificateBytes(alias)
        try {
            assertThrows(IllegalArgumentException::class.java) { store.delete(alias) }
            assertArrayEquals(before, certificateBytes(alias))
        } finally {
            androidKeyStore().deleteEntry(alias)
        }
    }

    @Test
    fun hardwareMaterialSnapshotsPublicBlobOnInputAndOutput() {
        val pair = softwareKeyPair("secp256r1")
        val source = SshPublicKeyCodec.encode(pair.public as ECPublicKey)
        val expected = source.copyOf()
        val material = HardwareKeyMaterial("herdroid.ssh.test", pair, source, HardwareSecurityLevel.TEE)

        source.fill(0)
        assertArrayEquals(expected, material.publicKeyOpenSsh())
        material.publicKeyOpenSsh().fill(0)
        assertArrayEquals(expected, material.publicKeyOpenSsh())
        material.close()
        assertArrayEquals(ByteArray(expected.size), material.publicKeyOpenSsh())
    }

    @Test
    fun claimedHardwareLevelCannotOverrideSoftwareKeyInfo() {
        val alias = newAlias()
        val pair = createAndroidKey(alias)
        val keyInfo = androidKeyInfo(pair)
        @Suppress("DEPRECATION")
        assumeFalse(keyInfo.isInsideSecureHardware)
        val blob = SshPublicKeyCodec.encode(pair.public as ECPublicKey)
        val material = HardwareKeyMaterial(alias, pair, blob, HardwareSecurityLevel.TEE)

        try {
            assertThrows(AssertionError::class.java) { assertMaterial(material, alias, blob) }
        } finally {
            material.close()
            blob.fill(0)
        }
    }

    @Test
    fun recognizesApi31StrongBoxUnavailableMessageWithoutBroadFallback() {
        assertTrue(
            isStrongBoxUnavailableForFallback(
                KeyStoreException("Requested security level (likely Strongbox) is not available."),
            ),
        )
        assertFalse(isStrongBoxUnavailableForFallback(KeyStoreException("StrongBox operation failed")))
        assertFalse(isStrongBoxUnavailableForFallback(KeyStoreException("storage is not available")))
    }

    private fun assertImportedOrUnavailable(
        alias: String,
        expectedBlob: ByteArray,
        result: Result<CreatedHardwareKey>,
    ) {
        result.onSuccess { imported ->
            imported.use {
                assertCreated(imported, alias, expectedBlob)
                store.load(alias).use { assertMaterial(it, alias, expectedBlob) }
            }
        }.onFailure {
            assertEquals(it.stackTraceToString(), HARDWARE_UNAVAILABLE, it.message)
            assertFalse(androidKeyStore().containsAlias(alias))
        }
    }

    private fun assertMaterial(material: HardwareKeyMaterial, alias: String, expectedBlob: ByteArray) {
        assertEquals(alias, material.alias)
        assertNull(material.keyPair.private.encoded)
        assertArrayEquals(expectedBlob, material.publicKeyOpenSsh())
        assertTrue(
            material.securityLevel == HardwareSecurityLevel.TEE ||
                material.securityLevel == HardwareSecurityLevel.STRONGBOX,
        )
        val keyInfo = androidKeyInfo(material.keyPair)
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX,
            )
        } else {
            @Suppress("DEPRECATION")
            assertTrue(keyInfo.isInsideSecureHardware)
        }
        val challenge = "herdroid-hardware-key-proof".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(material.keyPair.private)
            update(challenge)
            sign()
        }
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(material.keyPair.public)
            update(challenge)
            verify(signature)
        })
        assertTrue(material.toString().contains("privateKey=redacted"))
    }

    private fun assertCreated(material: CreatedHardwareKey, alias: String, expectedBlob: ByteArray) {
        assertEquals(alias, material.alias)
        assertArrayEquals(expectedBlob, material.publicKeyOpenSsh())
        assertTrue(material.fingerprint.startsWith("SHA256:"))
        assertTrue(
            material.securityLevel == HardwareSecurityLevel.TEE ||
                material.securityLevel == HardwareSecurityLevel.STRONGBOX,
        )
    }

    private fun softwareKeyPair(curve: String): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve))
        generateKeyPair()
    }

    private fun createAndroidKey(alias: String): KeyPair =
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }

    private fun androidKeyInfo(pair: KeyPair): KeyInfo =
        KeyFactory.getInstance(pair.private.algorithm, "AndroidKeyStore")
            .getKeySpec(pair.private, KeyInfo::class.java)

    private fun certificateBytes(alias: String) =
        requireNotNull(androidKeyStore().getCertificate(alias)).encoded

    private fun newAlias() = store.newAlias().also(aliases::add)

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val HARDWARE_UNAVAILABLE =
            "Hardware-backed key storage is unavailable on this device."
        private const val ENCRYPTED_OPENSSH_P256 = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABC0tK55Un
I9YrY6R5d+ZnaEAAAAGAAAAAEAAABoAAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlz
dHAyNTYAAABBBA8BRyh1vExpLAK2Hybju1pMf6FQbM4AaSe8Mxs3z6t3l+ugyqiY6+M+g6
Xpy9sOJh4difVXpuMVSJ/x8PD+klIAAACwvKsWmp8tdzDQWM6RPi0Df37nW7YTt/bcr5Ss
TByadoOEAsmqTPQlSOTq0DpwwMZPpLawoigN4XMGWJoSx/3I5jpf+9QDD0bDvRCO18gqcB
SgnpKJ4xhrcUmz2RFakJdqNKyvr76O/8Ij5BZqpBxUCYIoFuadP7IzIkFSLreDp+5a9KHi
D3PkofkfVR04BiWuj5oE00WDemfooSQKbEN/xEj1vRkWEIkGvqdTkDcNqDU=
-----END OPENSSH PRIVATE KEY-----"""
    }
}
