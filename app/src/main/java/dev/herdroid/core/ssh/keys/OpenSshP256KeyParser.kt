package dev.herdroid.core.ssh.keys

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.BlowfishEngine

internal object OpenSshP256KeyParser {
    private const val MAX_BCRYPT_ROUNDS = 256
    private const val INVALID = "Invalid OpenSSH P-256 private key"
    private val begin = "-----BEGIN OPENSSH PRIVATE KEY-----".encodeToByteArray()
    private val end = "-----END OPENSSH PRIVATE KEY-----".encodeToByteArray()
    private val authMagic = "openssh-key-v1\u0000".encodeToByteArray()
    private val p256 by lazy {
        AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun parse(document: ByteArray, passphrase: CharArray?): KeyPair {
        var decoded: ByteArray? = null
        var publicBlob: ByteArray? = null
        var privateBlock: ByteArray? = null
        var encryptedBlock: ByteArray? = null
        var kdfOptions: ByteArray? = null
        var passphraseBytes: ByteArray? = null
        var salt: ByteArray? = null
        var keyIv: ByteArray? = null
        var key: ByteArray? = null
        var iv: ByteArray? = null
        var point: ByteArray? = null
        var scalarBytes: ByteArray? = null
        try {
            decoded = decodePem(document)
            val envelope = Reader(decoded)
            require(envelope.read(authMagic.size).contentEquals(authMagic)) { INVALID }
            val cipherName = envelope.readAsciiString()
            val kdfName = envelope.readAsciiString()
            kdfOptions = envelope.readString()
            require(envelope.readUInt() == 1L) { INVALID }
            publicBlob = envelope.readString()
            encryptedBlock = envelope.readString()
            require(envelope.remaining == 0) { INVALID }

            privateBlock = when {
                cipherName == "none" && kdfName == "none" && kdfOptions.isEmpty() ->
                    encryptedBlock.copyOf()
                cipherName == "aes256-ctr" && kdfName == "bcrypt" -> {
                    val options = Reader(kdfOptions)
                    salt = options.readString()
                    val rounds = options.readUInt()
                    require(options.remaining == 0 && rounds > 0) { INVALID }
                    require(rounds <= MAX_BCRYPT_ROUNDS) {
                        "OpenSSH bcrypt rounds exceed $MAX_BCRYPT_ROUNDS"
                    }
                    require(passphrase != null) { INVALID }
                    passphraseBytes = passphrase.toUtf8()
                    keyIv = ByteArray(48)
                    OwnedBcryptPbkdf.derive(passphraseBytes, salt, rounds.toInt(), keyIv)
                    key = keyIv.copyOfRange(0, 32)
                    iv = keyIv.copyOfRange(32, 48)
                    Cipher.getInstance("AES/CTR/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        doFinal(encryptedBlock)
                    }
                }
                else -> throw IllegalArgumentException(INVALID)
            }

            val body = Reader(privateBlock)
            require(body.readUInt() == body.readUInt()) { INVALID }
            require(body.readAsciiString() == SshPublicKeyCodec.ALGORITHM) { INVALID }
            require(body.readAsciiString() == "nistp256") { INVALID }
            point = body.readString()
            scalarBytes = body.readString()
            body.readString().fill(0)
            var padding = 1
            while (body.remaining > 0) require(body.readByte() == padding++.toByte()) { INVALID }

            require(point.size == 65 && point[0] == 4.toByte()) { INVALID }
            val x = point.copyOfRange(1, 33)
            val y = point.copyOfRange(33, 65)
            val scalar = BigInteger(1, scalarBytes)
            try {
                require(scalar > BigInteger.ZERO && scalar < p256.order) { INVALID }
                val publicKey = KeyFactory.getInstance("EC").generatePublic(
                    ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), p256),
                ) as ECPublicKey
                require(MessageDigest.isEqual(publicBlob, SshPublicKeyCodec.encode(publicKey))) { INVALID }
                val privateKey = KeyFactory.getInstance("EC").generatePrivate(
                    ECPrivateKeySpec(scalar, p256),
                )
                return KeyPair(publicKey, privateKey)
            } finally {
                x.fill(0)
                y.fill(0)
            }
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalArgumentException(INVALID, failure)
        } finally {
            decoded?.fill(0)
            publicBlob?.fill(0)
            privateBlock?.fill(0)
            encryptedBlock?.fill(0)
            kdfOptions?.fill(0)
            passphraseBytes?.fill(0)
            salt?.fill(0)
            keyIv?.fill(0)
            key?.fill(0)
            iv?.fill(0)
            point?.fill(0)
            scalarBytes?.fill(0)
            document.fill(0)
            passphrase?.fill('\u0000')
        }
    }

    private fun decodePem(document: ByteArray): ByteArray {
        require(document.startsWith(begin)) { INVALID }
        var endOffset = document.size
        while (endOffset > 0 && (document[endOffset - 1] == '\n'.code.toByte() || document[endOffset - 1] == '\r'.code.toByte())) {
            endOffset--
        }
        require(endOffset >= end.size && document.regionMatches(endOffset - end.size, end)) { INVALID }
        val body = document.copyOfRange(begin.size, endOffset - end.size)
        return try {
            Base64.getMimeDecoder().decode(body)
        } finally {
            body.fill(0)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.regionMatches(offset: Int, expected: ByteArray) =
        expected.indices.all { this[offset + it] == expected[it] }

    private fun CharArray.toUtf8(): ByteArray {
        var size = 0
        var index = 0
        while (index < this.size) {
            val value = this[index++].code
            size += when {
                value < 0x80 -> 1
                value < 0x800 -> 2
                value !in 0xd800..0xdfff -> 3
                value <= 0xdbff && index < this.size && this[index].code in 0xdc00..0xdfff -> {
                    index++
                    4
                }
                else -> throw IllegalArgumentException(INVALID)
            }
        }
        val encoded = ByteArray(size)
        index = 0
        var output = 0
        while (index < this.size) {
            var value = this[index++].code
            when {
                value < 0x80 -> encoded[output++] = value.toByte()
                value < 0x800 -> {
                    encoded[output++] = (0xc0 or (value shr 6)).toByte()
                    encoded[output++] = (0x80 or (value and 0x3f)).toByte()
                }
                value !in 0xd800..0xdfff -> {
                    encoded[output++] = (0xe0 or (value shr 12)).toByte()
                    encoded[output++] = (0x80 or ((value shr 6) and 0x3f)).toByte()
                    encoded[output++] = (0x80 or (value and 0x3f)).toByte()
                }
                else -> {
                    value = 0x10000 + ((value - 0xd800) shl 10) + (this[index++].code - 0xdc00)
                    encoded[output++] = (0xf0 or (value shr 18)).toByte()
                    encoded[output++] = (0x80 or ((value shr 12) and 0x3f)).toByte()
                    encoded[output++] = (0x80 or ((value shr 6) and 0x3f)).toByte()
                    encoded[output++] = (0x80 or (value and 0x3f)).toByte()
                }
            }
        }
        return encoded
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining get() = bytes.size - offset

        fun readByte(): Byte {
            require(remaining > 0) { INVALID }
            return bytes[offset++]
        }

        fun read(size: Int): ByteArray {
            require(size >= 0 && size <= remaining) { INVALID }
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun readUInt(): Long {
            require(remaining >= 4) { INVALID }
            var value = 0L
            repeat(4) { value = (value shl 8) or (readByte().toLong() and 0xff) }
            return value
        }

        fun readString(): ByteArray {
            val size = readUInt()
            require(size <= Int.MAX_VALUE) { INVALID }
            return read(size.toInt())
        }

        fun readAsciiString(): String {
            val value = readString()
            return try {
                require(value.all { it.toInt() in 0..127 }) { INVALID }
                value.toString(Charsets.US_ASCII)
            } finally {
                value.fill(0)
            }
        }
    }
}

private object OwnedBcryptPbkdf {
    private val pSeed = seed("KP")
    private val sSeed = IntArray(1024).also { combined ->
        arrayOf("KS0", "KS1", "KS2", "KS3").forEachIndexed { index, name ->
            seed(name).copyInto(combined, index * 256)
        }
    }
    private val openBsdIv = intArrayOf(
        1333295459, 1752330093, 1635019107, 1114402679,
        1718186856, 1400332660, 1148808801, 1835627621,
    )

    fun derive(password: ByteArray, salt: ByteArray, rounds: Int, output: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-512")
        val shaPassword = digest.digest(password)
        val shaSalt = ByteArray(64)
        val countSalt = ByteArray(4)
        val block = ByteArray(32)
        val previous = ByteArray(32)
        try {
            val stride = (output.size + 31) / 32
            val amount = (output.size + stride - 1) / stride
            for (count in 1..stride) {
                countSalt[0] = (count ushr 24).toByte()
                countSalt[1] = (count ushr 16).toByte()
                countSalt[2] = (count ushr 8).toByte()
                countSalt[3] = count.toByte()
                digest.reset()
                digest.update(salt)
                digest.update(countSalt)
                digest.digest(shaSalt, 0, shaSalt.size)
                hash(shaPassword, shaSalt, previous)
                previous.copyInto(block)
                repeat(rounds - 1) {
                    digest.reset()
                    digest.update(previous)
                    digest.digest(shaSalt, 0, shaSalt.size)
                    hash(shaPassword, shaSalt, previous)
                    for (index in block.indices) block[index] = (block[index].toInt() xor previous[index].toInt()).toByte()
                }
                for (index in 0 until amount) {
                    val destination = index * stride + count - 1
                    if (destination < output.size) output[destination] = block[index]
                }
            }
        } finally {
            shaPassword.fill(0)
            shaSalt.fill(0)
            countSalt.fill(0)
            block.fill(0)
            previous.fill(0)
        }
    }

    private fun hash(password: ByteArray, salt: ByteArray, output: ByteArray) {
        val state = State(pSeed.copyOf(), sSeed.copyOf())
        val words = openBsdIv.copyOf()
        try {
            state.expand(salt, password)
            repeat(64) {
                state.key(salt)
                state.key(password)
            }
            repeat(64) {
                for (offset in words.indices step 2) state.encipher(words, offset)
            }
            var outputIndex = 0
            for (word in words) {
                output[outputIndex++] = word.toByte()
                output[outputIndex++] = (word ushr 8).toByte()
                output[outputIndex++] = (word ushr 16).toByte()
                output[outputIndex++] = (word ushr 24).toByte()
            }
        } finally {
            state.clear()
            words.fill(0)
        }
    }

    private class State(private val p: IntArray, private val s: IntArray) {
        fun key(key: ByteArray) {
            val keyOffset = intArrayOf(0)
            val words = intArrayOf(0, 0)
            try {
                for (index in p.indices) p[index] = p[index] xor streamToWord(key, keyOffset)
                for (index in p.indices step 2) {
                    encipher(words, 0)
                    p[index] = words[0]
                    p[index + 1] = words[1]
                }
                for (index in s.indices step 2) {
                    encipher(words, 0)
                    s[index] = words[0]
                    s[index + 1] = words[1]
                }
            } finally {
                keyOffset.fill(0)
                words.fill(0)
            }
        }

        fun expand(data: ByteArray, key: ByteArray) {
            val keyOffset = intArrayOf(0)
            val dataOffset = intArrayOf(0)
            val words = intArrayOf(0, 0)
            try {
                for (index in p.indices) p[index] = p[index] xor streamToWord(key, keyOffset)
                for (index in p.indices step 2) {
                    words[0] = words[0] xor streamToWord(data, dataOffset)
                    words[1] = words[1] xor streamToWord(data, dataOffset)
                    encipher(words, 0)
                    p[index] = words[0]
                    p[index + 1] = words[1]
                }
                for (index in s.indices step 2) {
                    words[0] = words[0] xor streamToWord(data, dataOffset)
                    words[1] = words[1] xor streamToWord(data, dataOffset)
                    encipher(words, 0)
                    s[index] = words[0]
                    s[index + 1] = words[1]
                }
            } finally {
                keyOffset.fill(0)
                dataOffset.fill(0)
                words.fill(0)
            }
        }

        fun encipher(words: IntArray, offset: Int) {
            var left = words[offset]
            var right = words[offset + 1]
            left = left xor p[0]
            var round = 0
            while (round <= 14) {
                right = right xor f(left) xor p[++round]
                left = left xor f(right) xor p[++round]
            }
            words[offset] = right xor p[17]
            words[offset + 1] = left
        }

        private fun f(value: Int): Int {
            var result = s[(value ushr 24) and 0xff]
            result += s[0x100 or ((value ushr 16) and 0xff)]
            result = result xor s[0x200 or ((value ushr 8) and 0xff)]
            return result + s[0x300 or (value and 0xff)]
        }

        private fun streamToWord(data: ByteArray, offset: IntArray): Int {
            var word = 0
            repeat(4) {
                word = (word shl 8) or (data[offset[0]].toInt() and 0xff)
                offset[0] = (offset[0] + 1) % data.size
            }
            return word
        }

        fun clear() {
            p.fill(0)
            s.fill(0)
        }
    }

    private fun seed(name: String): IntArray =
        BlowfishEngine::class.java.getDeclaredField(name).run {
            isAccessible = true
            (get(null) as IntArray).copyOf()
        }
}
