package dev.herdroid.core.ssh

import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.HostKeyDecision
import dev.herdroid.core.model.KnownHostRecord
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo

class HostKeyPolicyTest {
    @Test
    fun `Android EdDSA host key is encoded as canonical OpenSSH Ed25519`() {
        val androidKey = androidKey(publicKey.encoded)
        assertEquals(KeyType.UNKNOWN, KeyType.fromKey(androidKey))

        val decision = HostKeyPolicy.decide(Hop.TARGET, "host.example", 2222, androidKey, emptyList())

        assertTrue(decision is HostKeyDecision.Ask)
        val candidate = (decision as HostKeyDecision.Ask).candidate
        assertEquals("ssh-ed25519", candidate.algorithm)
        assertEquals(publicBlobBase64, candidate.keyBase64)
    }

    @Test
    fun `Android Ed25519 host key rejects malformed SPKI parameters and padding`() {
        val valid = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val algorithmWithNull = AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519, DERNull.INSTANCE)
        val paddedKey = DERBitString(valid.publicKeyData.bytes, 1)

        listOf(
            SubjectPublicKeyInfo(algorithmWithNull, valid.publicKeyData).encoded,
            SubjectPublicKeyInfo(AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), paddedKey).encoded,
        ).forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                HostKeyPolicy.decide(Hop.TARGET, "host.example", 2222, androidKey(encoded), emptyList())
            }
        }
    }

    @Test
    fun `unknown candidate contains exact persistable key and reconnect accepts for each hop`() {
        Hop.entries.forEach { hop ->
            val candidate = HostKeyCandidate(
                hop = hop,
                hostname = "host.example",
                port = 2222,
                algorithm = "ssh-ed25519",
                sha256 = "SHA256:akjDiNJR1FV2wtk0VSJTVFbl+r1BhGb6RzidbI5DXlE",
                keyBase64 = publicBlobBase64,
            )
            assertEquals(
                HostKeyDecision.Ask(candidate),
                HostKeyPolicy.decide(hop, "host.example", 2222, publicKey, emptyList()),
            )
            assertEquals(
                HostKeyDecision.Accept,
                HostKeyPolicy.decide(
                    hop,
                    "host.example",
                    2222,
                    publicKey,
                    listOf(candidate.toKnownHostRecord(acceptedAtEpochMillis = 42L)),
                ),
            )
        }
    }

    @Test
    fun `matching exact host port algorithm and key ignores unrelated records`() {
        val matching = record(
            hostname = "host.example",
            port = 2222,
            algorithm = "ssh-ed25519",
            keyBase64 = publicBlobBase64,
        )
        val distracting = listOf(
            record(hostname = "other.example", keyBase64 = changedPublicBlobBase64),
            record(port = 22, keyBase64 = changedPublicBlobBase64),
            record(algorithm = "ssh-rsa", keyBase64 = changedPublicBlobBase64),
        )

        assertEquals(
            HostKeyDecision.Accept,
            HostKeyPolicy.decide(Hop.JUMP, "host.example", 2222, publicKey, distracting + matching),
        )
    }

    @Test
    fun `changed key rejects without an approval path for each hop`() {
        val known = record(keyBase64 = changedPublicBlobBase64)

        Hop.entries.forEach { hop ->
            val decision = HostKeyPolicy.decide(hop, "host.example", 2222, publicKey, listOf(known))

            assertTrue(decision is HostKeyDecision.RejectChanged)
            assertEquals(
                HostKeyDecision.RejectChanged(
                    expected = HostKeyCandidate(
                        hop = hop,
                        hostname = "host.example",
                        port = 2222,
                        algorithm = "ssh-ed25519",
                        sha256 = "SHA256:9fn8+8Dxk7DGFTVylj6x8T6sDRwU63gF/BJdEM2/fDM",
                        keyBase64 = changedPublicBlobBase64,
                    ),
                    actual = HostKeyCandidate(
                        hop = hop,
                        hostname = "host.example",
                        port = 2222,
                        algorithm = "ssh-ed25519",
                        sha256 = "SHA256:akjDiNJR1FV2wtk0VSJTVFbl+r1BhGb6RzidbI5DXlE",
                        keyBase64 = publicBlobBase64,
                    ),
                ),
                decision,
            )
        }
    }

    private fun record(
        hostname: String = "host.example",
        port: Int = 2222,
        algorithm: String = "ssh-ed25519",
        keyBase64: String = publicBlobBase64,
    ) = KnownHostRecord(hostname, port, algorithm, keyBase64, acceptedAtEpochMillis = 1L)

    private fun androidKey(encoded: ByteArray) = object : PublicKey {
        override fun getAlgorithm() = "AndroidEd25519"
        override fun getFormat() = "X.509"
        override fun getEncoded() = encoded.copyOf()
    }

    private companion object {
        const val publicBlobBase64 =
            "AAAAC3NzaC1lZDI1NTE5AAAAIMGLx9+pdihxfSqUATudu4ZZjDsITeLzelS5Jl6Xxokx"
        const val changedPublicBlobBase64 =
            "AAAAC3NzaC1lZDI1NTE5AAAAIMGLx9+pdihxfSqUATudu4ZZjDsITeLzelS5Jl6Xxoky"

        val publicKey = Buffer.PlainBuffer(Base64.getDecoder().decode(publicBlobBase64)).readPublicKey()
    }
}
