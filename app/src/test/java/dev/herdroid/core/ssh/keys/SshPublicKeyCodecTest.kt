package dev.herdroid.core.ssh.keys

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SshPublicKeyCodecTest {
    @Test
    fun `formats canonical P256 public key and fingerprint`() {
        val blob = SshPublicKeyCodec.encode(fixedP256PublicKey())

        assertEquals(
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfU=",
            Base64.getEncoder().encodeToString(blob),
        )
        assertEquals(
            "SHA256:SFMVDOrhOSBssOVH9hIcK/Z2XVf1oSVMjNCL8u2aICY",
            SshPublicKeyCodec.fingerprint(blob),
        )
        assertEquals(
            "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(blob)} herdroid:phone",
            SshPublicKeyCodec.authorizedKeyLine(blob, "phone"),
        )
    }

    @Test
    fun `authorized key comments allow trimmed spaces and reject control injection`() {
        val blob = SshPublicKeyCodec.encode(fixedP256PublicKey())

        assertEquals(
            "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(blob)} herdroid:new key",
            SshPublicKeyCodec.authorizedKeyLine(blob, "  new key  "),
        )
        listOf("", "   ", "phone\nother", "phone\rother", "phone\tother", "phone\u0000other", "phone\u007fother").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                SshPublicKeyCodec.authorizedKeyLine(blob, name)
            }
        }
    }

    @Test
    fun `rejects malformed or relabelled public blobs`() {
        val blob = SshPublicKeyCodec.encode(fixedP256PublicKey())
        val relabelled = blob.copyOf().apply { this[4] = 'x'.code.toByte() }

        listOf(byteArrayOf(), blob.copyOf(blob.size - 1), blob + 0, relabelled).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                SshPublicKeyCodec.fingerprint(invalid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SshPublicKeyCodec.authorizedKeyLine(invalid, "phone")
            }
        }
    }

    @Test
    fun `rejects a correctly framed off curve point`() {
        val offCurve = SshPublicKeyCodec.encode(fixedP256PublicKey()).apply {
            fill(0, size - 64, size)
        }

        assertThrows(IllegalArgumentException::class.java) {
            SshPublicKeyCodec.fingerprint(offCurve)
        }
    }

    @Test
    fun `rejects non P256 public keys`() {
        val publicKey = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair().public as ECPublicKey
        }

        assertThrows(IllegalArgumentException::class.java) {
            SshPublicKeyCodec.encode(publicKey)
        }
    }

    private fun fixedP256PublicKey(): ECPublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(
                    BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
                    BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
                ),
                parameters,
            ),
        ) as ECPublicKey
    }
}
