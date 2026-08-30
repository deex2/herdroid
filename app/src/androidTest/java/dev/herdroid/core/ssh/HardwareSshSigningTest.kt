package dev.herdroid.core.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareSshSigningTest {
    private val alias = "herdroid.ssh.test.${UUID.randomUUID()}"

    @After
    fun deleteKey() {
        androidKeyStore().deleteEntry(alias)
    }

    @Test
    fun productionConfigSignsWithLoadedNonExportableAndroidKeyStoreP256Handle() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
        val entry = androidKeyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val pair = KeyPair(entry.certificate.publicKey, entry.privateKey)
        assertNull(pair.private.encoded)
        assertEquals(KeyType.ECDSA256, KeyPairWrapper(pair).type)
        SecurityUtils.setSecurityProvider("BC")

        val algorithm = productionSshConfig().keyAlgorithms
            .single { it.name == KeyType.ECDSA256.toString() }
            .create()
        assertNull(SecurityUtils.getSecurityProvider())
        val challenge = "android-keystore-sshj-p256".encodeToByteArray()
        val signer = algorithm.newSignature()
        signer.initSign(pair.private)
        signer.update(challenge)
        val derSignature = signer.sign()

        assertTrue(signer.encode(derSignature).isNotEmpty())
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(challenge)
            verify(derSignature)
        })
    }

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
