package dev.herdroid.core.data

import dev.herdroid.core.model.HardwareKeyMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DataBoundaryPolicyTest {
    @Test
    fun credentialTypesOwnArraysWithoutShallowCopies() {
        val passwordBytes = "secret".encodeToByteArray()
        val password = EndpointAuthenticationInput.Password(passwordBytes)
        val transactionCopy = password.copyForTransaction()

        password.close()

        assertArrayEquals(ByteArray(passwordBytes.size), passwordBytes)
        assertArrayEquals("secret".encodeToByteArray(), transactionCopy)
        assertFalse(EndpointAuthenticationInput.Password::class.java.hasGeneratedCopy())
        transactionCopy.fill(0)

        val publicKey = byteArrayOf(1, 2, 3)
        val key = ConnectionAuthenticationInput.HardwareKey(7, "alias", publicKey)
        publicKey.fill(9)
        val connectionCopy = key.copyPublicKeyForConnection()
        key.close()

        assertArrayEquals(byteArrayOf(1, 2, 3), connectionCopy)
        assertArrayEquals(ByteArray(3), key.copyPublicKeyForConnection())
        assertFalse(ConnectionAuthenticationInput.HardwareKey::class.java.hasGeneratedCopy())
        connectionCopy.fill(0)
        assertFalse(StoredKeyMetadata::class.java.declaredFields.any { it.type == ByteArray::class.java })
        assertFalse(HardwareKeyMetadata::class.java.declaredFields.any { it.type == ByteArray::class.java })
    }

    private fun Class<*>.hasGeneratedCopy() = declaredMethods.any { it.name == "copy" || it.name.startsWith("copy\$") }
}
