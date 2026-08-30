package dev.herdroid.core.ssh.keys

import java.nio.ByteBuffer
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSshP256KeyParserTest {
    @Test
    fun `parses encrypted P256 and wipes owned inputs`() {
        val document = ENCRYPTED.encodeToByteArray()
        val passphrase = "test-passphrase".toCharArray()

        val pair = OpenSshP256KeyParser.parse(document, passphrase)

        assertPair(pair.public as ECPublicKey, pair.private, ENCRYPTED_PUBLIC_BLOB)
        assertTrue(document.all { it == 0.toByte() })
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun `parses unencrypted P256 and wipes owned input`() {
        val document = UNENCRYPTED.encodeToByteArray()

        val pair = OpenSshP256KeyParser.parse(document, null)

        assertPair(pair.public as ECPublicKey, pair.private, UNENCRYPTED_PUBLIC_BLOB)
        assertTrue(document.all { it == 0.toByte() })
    }

    @Test
    fun `rejects excessive bcrypt rounds before KDF and wipes inputs`() {
        val document = withBcryptRounds(ENCRYPTED, 257).encodeToByteArray()
        val passphrase = "test-passphrase".toCharArray()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            OpenSshP256KeyParser.parse(document, passphrase)
        }

        assertEquals("OpenSSH bcrypt rounds exceed 256", failure.message)
        assertTrue(document.all { it == 0.toByte() })
        assertTrue(passphrase.all { it == '\u0000' })
    }

    private fun assertPair(
        publicKey: ECPublicKey,
        privateKey: java.security.PrivateKey,
        expectedBlobBase64: String,
    ) {
        assertArrayEquals(
            Base64.getDecoder().decode(expectedBlobBase64),
            SshPublicKeyCodec.encode(publicKey),
        )
        val challenge = "owned-openssh-parser".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(signature)
        })
    }

    private fun withBcryptRounds(pem: String, rounds: Int): String {
        val decoded = Base64.getMimeDecoder().decode(
            pem.substringAfter("-----BEGIN OPENSSH PRIVATE KEY-----")
                .substringBefore("-----END OPENSSH PRIVATE KEY-----"),
        )
        var offset = "openssh-key-v1\u0000".encodeToByteArray().size
        repeat(2) { offset = skipString(decoded, offset) }
        val optionsSize = ByteBuffer.wrap(decoded, offset, 4).int
        val optionsStart = offset + 4
        val saltSize = ByteBuffer.wrap(decoded, optionsStart, 4).int
        check(optionsSize >= 4 + saltSize + 4)
        ByteBuffer.wrap(decoded, optionsStart + 4 + saltSize, 4).putInt(rounds)
        val encoded = Base64.getMimeEncoder(70, "\n".encodeToByteArray()).encodeToString(decoded)
        decoded.fill(0)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$encoded\n-----END OPENSSH PRIVATE KEY-----"
    }

    private fun skipString(bytes: ByteArray, offset: Int): Int =
        offset + 4 + ByteBuffer.wrap(bytes, offset, 4).int

    companion object {
        private const val ENCRYPTED_PUBLIC_BLOB =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBA8BRyh1vExpLAK2Hybju1pMf6FQbM4AaSe8Mxs3z6t3l+ugyqiY6+M+g6Xpy9sOJh4difVXpuMVSJ/x8PD+klI="
        private const val UNENCRYPTED_PUBLIC_BLOB =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKGPI+ZGemVPdmM4tS7k4Yn/7AMIA2XiqtKjbFD7HeYENIc2HR/zFrQYGCcKkGxbVoTpVewhW2+a3SirVeGz7ks="
        private const val ENCRYPTED = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABC0tK55Un
I9YrY6R5d+ZnaEAAAAGAAAAAEAAABoAAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlz
dHAyNTYAAABBBA8BRyh1vExpLAK2Hybju1pMf6FQbM4AaSe8Mxs3z6t3l+ugyqiY6+M+g6
Xpy9sOJh4difVXpuMVSJ/x8PD+klIAAACwvKsWmp8tdzDQWM6RPi0Df37nW7YTt/bcr5Ss
TByadoOEAsmqTPQlSOTq0DpwwMZPpLawoigN4XMGWJoSx/3I5jpf+9QDD0bDvRCO18gqcB
SgnpKJ4xhrcUmz2RFakJdqNKyvr76O/8Ij5BZqpBxUCYIoFuadP7IzIkFSLreDp+5a9KHi
D3PkofkfVR04BiWuj5oE00WDemfooSQKbEN/xEj1vRkWEIkGvqdTkDcNqDU=
-----END OPENSSH PRIVATE KEY-----"""
        private const val UNENCRYPTED = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAaAAAABNlY2RzYS
1zaGEyLW5pc3RwMjU2AAAACG5pc3RwMjU2AAAAQQShjyPmRnplT3ZjOLUu5OGJ/+wDCANl
4qrSo2xQ+x3mBDSHNh0f8xa0GBgnCpBsW1aE6VXsIVtvmt0oq1Xhs+5LAAAAsFgd/SNYHf
0jAAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKGPI+ZGemVPdmM4
tS7k4Yn/7AMIA2XiqtKjbFD7HeYENIc2HR/zFrQYGCcKkGxbVoTpVewhW2+a3SirVeGz7k
sAAAAgLn4YS+NKh8UAIvQhzzLzTHAXOYdg94Iq0LBc5LebfHkAAAAUaGVyZHJvaWQtdW5l
bmNyeXB0ZWQBAgME
-----END OPENSSH PRIVATE KEY-----"""
    }
}
