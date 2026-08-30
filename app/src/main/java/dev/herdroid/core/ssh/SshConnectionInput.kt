package dev.herdroid.core.ssh

class SshConnectionInput(
    val routeName: String,
    val target: SshEndpointInput,
    val jump: SshEndpointInput?,
) : AutoCloseable {
    override fun close() {
        target.close()
        jump?.close()
    }
}

class SshEndpointInput(
    val hostname: String,
    val port: Int,
    val username: String,
    val authentication: SshAuthenticationInput,
    val herdrPath: String?,
) : AutoCloseable {
    override fun close() = authentication.close()
}

sealed interface SshAuthenticationInput : AutoCloseable {
    class Password(private val ownedBytes: ByteArray) : SshAuthenticationInput {
        internal fun copyForAuthentication(): ByteArray = ownedBytes.copyOf()
        override fun close() = ownedBytes.fill(0)
        override fun toString() = "Password(redacted)"
    }

    class HardwareKey(
        val keyId: Long,
        val alias: String,
        publicKeyOpenSsh: ByteArray,
    ) : SshAuthenticationInput {
        private val ownedPublicKey = publicKeyOpenSsh.copyOf()
        fun publicKeyOpenSsh(): ByteArray = ownedPublicKey.copyOf()
        override fun close() = ownedPublicKey.fill(0)
    }
}
