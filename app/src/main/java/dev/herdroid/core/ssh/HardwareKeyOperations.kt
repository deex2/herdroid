package dev.herdroid.core.ssh

import dev.herdroid.core.model.HardwareSecurityLevel

class CreatedHardwareKey(
    val alias: String,
    publicKeyOpenSsh: ByteArray,
    val fingerprint: String,
    val securityLevel: HardwareSecurityLevel,
) : AutoCloseable {
    private val ownedPublicKey = publicKeyOpenSsh.copyOf()
    fun publicKeyOpenSsh(): ByteArray = ownedPublicKey.copyOf()
    override fun close() = ownedPublicKey.fill(0)
}

interface HardwareKeyOperations {
    fun newAlias(): String
    fun generate(alias: String): CreatedHardwareKey
    fun importKey(alias: String, document: ByteArray, passphrase: CharArray?): CreatedHardwareKey
    fun delete(alias: String)
}
