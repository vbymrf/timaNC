package com.tima.client.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.SecureStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecureStorage(context: Context) : SecureStorage {
    private val preferences =
        context.applicationContext.getSharedPreferences("tima.secure.v1", Context.MODE_PRIVATE)

    override suspend fun read(key: String): ByteArray? {
        val encoded = preferences.getString(validKey(key), null) ?: return null
        return runCatching {
            val value = Base64.decode(encoded, Base64.NO_WRAP)
            require(value.size > IV_BYTES)
            decrypt(value.copyOfRange(0, IV_BYTES), value.copyOfRange(IV_BYTES, value.size))
        }.getOrElse {
            throw PlatformServiceUnavailableException("Android Keystore secure storage", it)
        }
    }

    override suspend fun write(key: String, value: ByteArray) {
        require(value.isNotEmpty()) { "secure values must not be empty" }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.iv + cipher.doFinal(value.copyOf())
            check(
                preferences.edit()
                    .putString(validKey(key), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit(),
            ) { "encrypted preference commit failed" }
        }.getOrElse {
            throw PlatformServiceUnavailableException("Android Keystore secure storage", it)
        }
    }

    override suspend fun delete(key: String) {
        check(preferences.edit().remove(validKey(key)).commit()) {
            "encrypted preference deletion failed"
        }
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun validKey(key: String): String {
        require(key.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "invalid secure-storage key" }
        return key
    }

    private companion object {
        const val KEY_ALIAS = "tima.phase1.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
