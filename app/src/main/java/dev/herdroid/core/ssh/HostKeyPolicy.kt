package dev.herdroid.core.ssh

import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.HostKeyDecision
import dev.herdroid.core.model.KnownHostRecord
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo

object HostKeyPolicy {
    fun decide(
        hop: Hop,
        hostname: String,
        port: Int,
        key: PublicKey,
        knownHosts: List<KnownHostRecord>,
    ): HostKeyDecision {
        val (algorithm, blob) = canonicalHostKey(key)
        val actual = HostKeyCandidate(
            hop = hop,
            hostname = hostname,
            port = port,
            algorithm = algorithm,
            sha256 = fingerprint(blob),
            keyBase64 = Base64.getEncoder().encodeToString(blob),
        )
        val known = knownHosts.firstOrNull {
            it.hostname == hostname && it.port == port && it.algorithm == algorithm
        } ?: return HostKeyDecision.Ask(actual)

        val knownBlob = try {
            Base64.getDecoder().decode(known.keyBase64)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("Stored host key is not valid Base64", failure)
        }
        return if (knownBlob.contentEquals(blob)) {
            HostKeyDecision.Accept
        } else {
            HostKeyDecision.RejectChanged(
                expected = HostKeyCandidate(
                    hop = hop,
                    hostname = hostname,
                    port = port,
                    algorithm = algorithm,
                    sha256 = fingerprint(knownBlob),
                    keyBase64 = Base64.getEncoder().encodeToString(knownBlob),
                ),
                actual = actual,
            )
        }
    }

    private fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob),
        )

    private fun canonicalHostKey(key: PublicKey): Pair<String, ByteArray> {
        val type = KeyType.fromKey(key)
        if (type != KeyType.UNKNOWN) {
            return type.toString() to Buffer.PlainBuffer().putPublicKey(key).compactData
        }
        val info = SubjectPublicKeyInfo.getInstance(requireNotNull(key.encoded) { "Missing host key encoding" })
        require(info.algorithm.algorithm == EdECObjectIdentifiers.id_Ed25519) { "Unsupported EdDSA host key" }
        require(info.algorithm.parameters == null) { "Invalid Ed25519 host key parameters" }
        require(info.publicKeyData.padBits == 0) { "Invalid Ed25519 host key padding" }
        val raw = info.publicKeyData.octets
        require(raw.size == 32) { "Invalid Ed25519 host key" }
        return ED25519 to Buffer.PlainBuffer().putString(ED25519).putString(raw).compactData
    }

    private const val ED25519 = "ssh-ed25519"
}
