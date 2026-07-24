package com.tima.client.data

import com.tima.client.database.TimaDatabase
import com.tima.client.media.EncryptedMediaBlobStore
import com.tima.client.media.MediaVariantName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable ciphertext-only blob storage for platforms that do not use app-private files. */
class SqlDelightCiphertextMediaBlobStore(database: TimaDatabase) : EncryptedMediaBlobStore {
    private val queries = database.phase1Queries
    private val mutex = Mutex()

    override suspend fun write(
        localId: String,
        name: MediaVariantName,
        ciphertext: ByteArray,
    ): String = mutex.withLock {
        require(localId.matches(Regex("^[A-Za-z0-9-]{1,80}$")))
        val blobId = "$localId-${name.wireValue}"
        queries.upsertMediaCipherBlob(blobId, ciphertext)
        blobId
    }

    override suspend fun read(blobId: String): ByteArray = mutex.withLock {
        requireBlobId(blobId)
        queries.selectMediaCipherBlob(blobId).executeAsOne().copyOf()
    }

    override suspend fun delete(blobId: String) {
        mutex.withLock {
            requireBlobId(blobId)
            queries.deleteMediaCipherBlob(blobId)
        }
    }

    override suspend fun clear() {
        mutex.withLock { queries.deleteAllMediaCipherBlobs() }
    }

    private fun requireBlobId(value: String) {
        require(value.matches(Regex("^[A-Za-z0-9-]{1,100}-(thumbnail|preview|full)$")))
    }
}
