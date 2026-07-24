package com.tima.client.crypto

import io.kodium.Kodium

/**
 * Narrow authenticated-encryption boundary for local row payloads.
 *
 * The wire prefix belongs to the cache format, not Kodium, so incompatible
 * future formats fail closed instead of being interpreted as current data.
 */
object LocalCacheCrypto {
    private val prefix = byteArrayOf(0x54, 0x49, 0x4d, 0x41, 0x01)

    fun generateKey(): ByteArray = Kodium.generateHighEntropyKey().also {
        check(it.size == KEY_BYTES) { "Kodium returned an invalid cache key size" }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        requireKey(key)
        require(plaintext.isNotEmpty()) { "cache payload must not be empty" }
        val workingKey = key.copyOf()
        val workingPlaintext = plaintext.copyOf()
        return try {
            prefix + Kodium.encryptSymmetric(workingKey, workingPlaintext).getOrThrow()
        } finally {
            workingKey.fill(0)
            workingPlaintext.fill(0)
        }
    }

    fun decrypt(key: ByteArray, encoded: ByteArray): ByteArray {
        requireKey(key)
        require(encoded.size > prefix.size && encoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "unsupported or malformed local cache ciphertext"
        }
        val workingKey = key.copyOf()
        val ciphertext = encoded.copyOfRange(prefix.size, encoded.size)
        return try {
            Kodium.decryptSymmetric(workingKey, ciphertext).getOrThrow()
        } finally {
            workingKey.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun requireKey(key: ByteArray) {
        require(key.size == KEY_BYTES) { "local cache key must be 32 bytes" }
    }

    const val KEY_BYTES = 32
}
