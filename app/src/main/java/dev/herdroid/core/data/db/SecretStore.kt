package dev.herdroid.core.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun loadOrCreate(databaseExists: Boolean): ByteArray {
        val hasIv = preferences.contains(IV_KEY)
        val hasCiphertext = preferences.contains(CIPHERTEXT_KEY)
        if (hasIv != hasCiphertext || databaseExists && !hasIv) throw GeneralSecurityException("Missing wrapped database key")
        if (hasIv) return unwrap()

        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            ciphertext = cipher.doFinal(passphrase)
            check(
                preferences.edit()
                    .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit()
            ) { "Could not persist wrapped database key" }
            return passphrase
        } catch (failure: Exception) {
            passphrase.fill(0)
            throw failure
        } finally {
            ciphertext?.fill(0)
        }
    }

    private fun unwrap(): ByteArray {
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            iv = Base64.decode(requireNotNull(preferences.getString(IV_KEY, null)), Base64.NO_WRAP)
            ciphertext = Base64.decode(requireNotNull(preferences.getString(CIPHERTEXT_KEY, null)), Base64.NO_WRAP)
            if (iv.size != 12 || ciphertext.isEmpty()) throw GeneralSecurityException("Invalid wrapped database key")
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw GeneralSecurityException("Missing database wrapping key")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext).also {
                if (it.size != 32) {
                    it.fill(0)
                    throw GeneralSecurityException("Invalid database key size")
                }
            }
        } catch (failure: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid wrapped database key", failure)
        } finally {
            iv?.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "herdroid_local_secrets"
        const val IV_KEY = "database_passphrase_iv"
        const val CIPHERTEXT_KEY = "database_passphrase_ciphertext"
        const val KEY_ALIAS = "dev.herdroid.database.wrap.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
