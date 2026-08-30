package dev.herdroid.core.ssh.keys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.StrongBoxUnavailableException
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.ssh.CreatedHardwareKey
import dev.herdroid.core.ssh.HardwareKeyOperations
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

internal class HardwareKeyMaterial(
    val alias: String,
    val keyPair: KeyPair,
    publicKeyOpenSsh: ByteArray,
    val securityLevel: HardwareSecurityLevel,
) : AutoCloseable {
    private val publicBlob = publicKeyOpenSsh.copyOf()
    fun publicKeyOpenSsh(): ByteArray = publicBlob.copyOf()

    override fun close() = publicBlob.fill(0)

    override fun toString() =
        "HardwareKeyMaterial(alias=$alias, privateKey=redacted, securityLevel=$securityLevel)"
}

@Singleton
internal class HardwareSshKeyStore @Inject constructor() : HardwareKeyOperations {
    override fun newAlias(): String = "$ALIAS_PREFIX${UUID.randomUUID()}"

    override fun generate(alias: String): CreatedHardwareKey = generateMaterial(alias).use(HardwareKeyMaterial::toCreated)

    private fun generateMaterial(alias: String): HardwareKeyMaterial {
        requireNewAlias(alias)
        try {
            generate(alias, strongBox = true)
            return load(alias)
        } catch (failure: Exception) {
            delete(alias)
            if (!isStrongBoxUnavailableForFallback(failure)) throw failure
        }
        return try {
            generate(alias, strongBox = false)
            load(alias)
        } catch (failure: Exception) {
            delete(alias)
            throw failure
        }
    }

    override fun importKey(alias: String, document: ByteArray, passphrase: CharArray?): CreatedHardwareKey =
        importMaterial(alias, document, passphrase).use(HardwareKeyMaterial::toCreated)

    internal fun importMaterial(
        alias: String,
        document: ByteArray,
        passphrase: CharArray?,
        loadImported: ((String) -> HardwareKeyMaterial)? = null,
    ): HardwareKeyMaterial {
        var copy: ByteArray? = null
        var decoded: ByteArray? = null
        var challenge: ByteArray? = null
        var signature: ByteArray? = null
        var expectedBlob: ByteArray? = null
        var ownsAlias = false
        try {
            requireNewAlias(alias)
            require(document.size <= SshPublicKeyCodec.MAX_DOCUMENT_BYTES) { SUPPORTED_P256 }
            copy = document.copyOf()
            val pair = try {
                if (copy.hasOpenSshEnvelope()) {
                    OpenSshP256KeyParser.parse(copy, passphrase)
                } else {
                    decoded = copy.copyOf()
                    val privateKey = KeyFactory.getInstance("EC")
                        .generatePrivate(PKCS8EncodedKeySpec(decoded)) as? ECPrivateKey
                        ?: throw IllegalArgumentException(SUPPORTED_P256)
                    KeyPair(privateKey.derivePublicKey(), privateKey)
                }
            } catch (failure: Exception) {
                throw IllegalArgumentException(SUPPORTED_P256, failure)
            }
            val privateKey = pair.private as? ECPrivateKey
                ?: throw IllegalArgumentException(SUPPORTED_P256)
            val publicKey = pair.public as? ECPublicKey
                ?: throw IllegalArgumentException(SUPPORTED_P256)
            require(privateKey.params.isP256()) { SUPPORTED_P256 }
            expectedBlob = SshPublicKeyCodec.encode(publicKey)
            challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            signature = sign(privateKey, challenge)
            require(verify(publicKey, challenge, signature)) { SUPPORTED_P256 }
            val certificate = createCertificate(pair)

            ownsAlias = true
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    insert(alias, privateKey, certificate, strongBox = true)
                } catch (failure: Exception) {
                    delete(alias)
                    if (!isStrongBoxUnavailableForFallback(failure)) throw failure
                    insert(alias, privateKey, certificate, strongBox = false)
                }
            } else {
                insert(alias, privateKey, certificate, strongBox = false)
            }
            val imported = loadImported?.invoke(alias) ?: load(alias)
            try {
                val importedBlob = imported.publicKeyOpenSsh()
                try {
                    require(MessageDigest.isEqual(requireNotNull(expectedBlob), importedBlob)) { SUPPORTED_P256 }
                    return imported
                } finally {
                    importedBlob.fill(0)
                }
            } catch (failure: Throwable) {
                imported.close()
                throw failure
            }
        } catch (failure: IllegalArgumentException) {
            if (ownsAlias) delete(alias)
            if (failure.message == HARDWARE_UNAVAILABLE) throw failure
            throw IllegalArgumentException(SUPPORTED_P256, failure)
        } catch (failure: Exception) {
            if (ownsAlias) delete(alias)
            throw failure
        } finally {
            copy?.fill(0)
            decoded?.fill(0)
            challenge?.fill(0)
            signature?.fill(0)
            expectedBlob?.fill(0)
            document.fill(0)
            passphrase?.fill('\u0000')
        }
    }

    internal fun load(
        alias: String,
        securityLevelFor: ((PrivateKey) -> HardwareSecurityLevel?)? = null,
        encodePublicKey: ((ECPublicKey) -> ByteArray)? = null,
        signChallenge: ((PrivateKey, ByteArray) -> ByteArray)? = null,
    ): HardwareKeyMaterial {
        requireAlias(alias)
        val entry = androidKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException(MISSING_KEY)
        val privateKey = entry.privateKey
        val publicKey = entry.certificate.publicKey as? ECPublicKey
            ?: return refuse(alias)
        val securityLevel = (securityLevelFor ?: ::hardwareSecurityLevel)(privateKey) ?: return refuse(alias)
        val publicBlob = try {
            (encodePublicKey ?: SshPublicKeyCodec::encode)(publicKey)
        } catch (_: Exception) {
            return refuse(alias)
        }
        try {
            val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            var signature: ByteArray? = null
            try {
                signature = (signChallenge ?: ::sign)(privateKey, challenge)
                if (!verify(publicKey, challenge, signature)) return refuse(alias)
            } catch (_: Exception) {
                return refuse(alias)
            } finally {
                challenge.fill(0)
                signature?.fill(0)
            }
            return HardwareKeyMaterial(alias, KeyPair(publicKey, privateKey), publicBlob, securityLevel)
        } finally {
            publicBlob.fill(0)
        }
    }

    override fun delete(alias: String) {
        requireAlias(alias)
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun generate(alias: String, strongBox: Boolean) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private fun insert(
        alias: String,
        privateKey: PrivateKey,
        certificate: java.security.cert.Certificate,
        strongBox: Boolean,
    ) {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= 31) protection.setIsStrongBoxBacked(strongBox)
        androidKeyStore().setEntry(
            alias,
            KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
            protection.build(),
        )
    }

    private fun hardwareSecurityLevel(privateKey: PrivateKey): HardwareSecurityLevel? {
        val keyInfo = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEY_STORE)
            .getKeySpec(privateKey, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            return when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> HardwareSecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> HardwareSecurityLevel.TEE
                else -> null
            }
        }
        @Suppress("DEPRECATION")
        return if (keyInfo.isInsideSecureHardware) HardwareSecurityLevel.TEE else null
    }

    private fun refuse(alias: String): Nothing {
        delete(alias)
        throw IllegalArgumentException(HARDWARE_UNAVAILABLE)
    }

    private fun createCertificate(pair: KeyPair): java.security.cert.X509Certificate {
        val provider = BouncyCastleProvider()
        val subject = X500Name("CN=Herdroid imported SSH key")
        val now = System.currentTimeMillis()
        val serial = BigInteger(160, SecureRandom()).max(BigInteger.ONE)
        val certificate = JcaX509v3CertificateBuilder(
            subject,
            serial,
            Date(now - 60_000),
            Date(now + 86_400_000),
            subject,
            pair.public,
        ).build(
            JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(provider)
                .build(pair.private),
        )
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(certificate)
    }

    private fun ECPrivateKey.derivePublicKey(): ECPublicKey {
        require(params.isP256()) { SUPPORTED_P256 }
        var result = ECPoint.POINT_INFINITY
        var addend = params.generator
        var scalar = s
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = params.add(result, addend)
            addend = params.add(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        require(result != ECPoint.POINT_INFINITY) { SUPPORTED_P256 }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(result, params)) as ECPublicKey
    }

    private fun ECParameterSpec.add(left: ECPoint, right: ECPoint): ECPoint {
        if (left == ECPoint.POINT_INFINITY) return right
        if (right == ECPoint.POINT_INFINITY) return left
        val prime = (curve.field as java.security.spec.ECFieldFp).p
        if (left.affineX == right.affineX && left.affineY.add(right.affineY).mod(prime) == BigInteger.ZERO) {
            return ECPoint.POINT_INFINITY
        }
        val slope = if (left == right) {
            left.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(curve.a)
                .multiply(left.affineY.shiftLeft(1).modInverse(prime)).mod(prime)
        } else {
            right.affineY.subtract(left.affineY)
                .multiply(right.affineX.subtract(left.affineX).mod(prime).modInverse(prime)).mod(prime)
        }
        val x = slope.pow(2).subtract(left.affineX).subtract(right.affineX).mod(prime)
        val y = slope.multiply(left.affineX.subtract(x)).subtract(left.affineY).mod(prime)
        return ECPoint(x, y)
    }

    private fun ECParameterSpec.isP256(): Boolean =
        curve.field.fieldSize == 256 && order == P256_ORDER && cofactor == 1

    private fun ByteArray.hasOpenSshEnvelope() =
        size >= OPENSSH_BEGIN.size && OPENSSH_BEGIN.indices.all { this[it] == OPENSSH_BEGIN[it] }

    private fun requireAlias(alias: String) {
        require(alias.startsWith(ALIAS_PREFIX) && alias.length > ALIAS_PREFIX.length)
    }

    private fun requireNewAlias(alias: String) {
        requireAlias(alias)
        require(!androidKeyStore().containsAlias(alias)) { "Alias already exists" }
    }

    private fun sign(privateKey: PrivateKey, challenge: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }

    private fun verify(publicKey: ECPublicKey, challenge: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(signature)
        }

    private fun androidKeyStore() = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "herdroid.ssh."
        private const val HARDWARE_UNAVAILABLE =
            "Hardware-backed key storage is unavailable on this device."
        private const val MISSING_KEY = "Hardware key unavailable. Select or create a replacement key."
        private const val SUPPORTED_P256 = "Only ECDSA P-256 SSH keys are supported."
        private val OPENSSH_BEGIN =
            "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray(StandardCharsets.US_ASCII)
        private val P256_ORDER =
            BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    }
}

private fun HardwareKeyMaterial.toCreated(): CreatedHardwareKey {
    val publicKey = publicKeyOpenSsh()
    return try {
        CreatedHardwareKey(
            alias,
            publicKey,
            SshPublicKeyCodec.fingerprint(publicKey),
            securityLevel,
        )
    } finally {
        publicKey.fill(0)
    }
}

internal fun isStrongBoxUnavailableForFallback(failure: Throwable): Boolean =
    generateSequence(failure as Throwable?) { it.cause }.any {
        it is StrongBoxUnavailableException ||
            (Build.VERSION.SDK_INT >= 33 &&
                it is android.security.KeyStoreException &&
                it.numericErrorCode == -68) ||
            it.message == "Requested security level (likely Strongbox) is not available."
    }
