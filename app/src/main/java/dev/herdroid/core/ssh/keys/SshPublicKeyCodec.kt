package dev.herdroid.core.ssh.keys

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

object SshPublicKeyCodec {
    internal const val ALGORITHM = "ecdsa-sha2-nistp256"
    internal const val MAX_DOCUMENT_BYTES = 256 * 1024
    private const val CURVE = "nistp256"
    private val p256Parameters by lazy {
        AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
    }

    internal fun encode(publicKey: ECPublicKey): ByteArray {
        require(publicKey.params.isP256()) { "Only P-256 public keys are supported" }
        val point = byteArrayOf(4) + publicKey.w.affineX.unsigned32() + publicKey.w.affineY.unsigned32()
        return ByteArrayOutputStream().apply {
            writeSshString(ALGORITHM.encodeToByteArray())
            writeSshString(CURVE.encodeToByteArray())
            writeSshString(point)
        }.toByteArray()
    }

    fun authorizedKeyLine(publicBlob: ByteArray, name: String): String {
        val snapshot = publicBlob.copyOf()
        try {
            validate(snapshot)
            val comment = normalizeComment(name)
            return "$ALGORITHM ${Base64.getEncoder().encodeToString(snapshot)} herdroid:$comment"
        } finally {
            snapshot.fill(0)
        }
    }

    fun normalizeComment(name: String): String = name.trim().also { comment ->
        require(comment.isNotEmpty()) { "Key name must not be blank" }
        require(comment.none { Character.isISOControl(it.code) }) { "Key name contains control characters" }
    }

    internal fun fingerprint(publicBlob: ByteArray): String {
        val snapshot = publicBlob.copyOf()
        try {
            validate(snapshot)
            val digest = MessageDigest.getInstance("SHA-256").digest(snapshot)
            return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
        } finally {
            snapshot.fill(0)
        }
    }

    private fun validate(publicBlob: ByteArray) {
        val buffer = ByteBuffer.wrap(publicBlob)
        require(buffer.readSshString().contentEquals(ALGORITHM.toByteArray(StandardCharsets.US_ASCII)))
        require(buffer.readSshString().contentEquals(CURVE.toByteArray(StandardCharsets.US_ASCII)))
        val point = buffer.readSshString()
        require(!buffer.hasRemaining() && point.size == 65 && point[0] == 4.toByte())
        val x = point.copyOfRange(1, 33)
        val y = point.copyOfRange(33, 65)
        try {
            val affineX = x.toUnsignedBigInteger()
            val affineY = y.toUnsignedBigInteger()
            val prime = (p256Parameters.curve.field as java.security.spec.ECFieldFp).p
            require(affineX < prime && affineY < prime)
            require(
                affineY.modPow(java.math.BigInteger.valueOf(2), prime) ==
                    affineX.modPow(java.math.BigInteger.valueOf(3), prime)
                        .add(p256Parameters.curve.a.multiply(affineX))
                        .add(p256Parameters.curve.b)
                        .mod(prime),
            )
            KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(
                    ECPoint(affineX, affineY),
                    p256Parameters,
                ),
            )
        } finally {
            x.fill(0)
            y.fill(0)
            point.fill(0)
        }
    }

    private fun ECParameterSpec.isP256(): Boolean =
        curve == p256Parameters.curve &&
            generator == p256Parameters.generator &&
            order == p256Parameters.order &&
            cofactor == p256Parameters.cofactor

    private fun java.math.BigInteger.unsigned32(): ByteArray {
        require(signum() >= 0 && bitLength() <= 256)
        val encoded = toByteArray()
        return ByteArray(32).also { result ->
            val offset = if (encoded.size == 33 && encoded[0] == 0.toByte()) 1 else 0
            encoded.copyInto(result, 32 - (encoded.size - offset), offset)
            encoded.fill(0)
        }
    }

    private fun ByteArray.toUnsignedBigInteger() = java.math.BigInteger(1, this)

    private fun ByteArrayOutputStream.writeSshString(value: ByteArray) {
        write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        write(value)
    }

    private fun ByteBuffer.readSshString(): ByteArray {
        require(remaining() >= Int.SIZE_BYTES)
        val size = int
        require(size >= 0 && size <= remaining())
        return ByteArray(size).also(::get)
    }
}
