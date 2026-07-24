package com.tima.client.data

import com.tima.client.crypto.LocalCacheCrypto
import com.tima.client.database.TimaDatabase
import com.tima.client.media.MediaQueueRecord
import com.tima.client.media.MediaQueueStore
import com.tima.client.network.SecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encrypts the complete retry record, including media keys and presigned URLs, before SQLite.
 * Queryable columns contain identifiers and state only.
 */
class EncryptedSqlDelightMediaQueueStore(
    database: TimaDatabase,
    private val secureStorage: SecureStorage,
) : MediaQueueStore {
    private val queries = database.phase1Queries
    private val mutex = Mutex()
    private var keyInMemory: ByteArray? = null

    override suspend fun put(record: MediaQueueRecord) {
        mutex.withLock {
            require(record.createdAtEpochMillis > 0)
            val plaintext = json.encodeToString(record).encodeToByteArray()
            val encrypted = try {
                LocalCacheCrypto.encrypt(key(), plaintext)
            } finally {
                plaintext.fill(0)
            }
            queries.transaction {
                val existing = queries.selectMediaQueueById(record.localId).executeAsOneOrNull()
                if (existing == null) {
                    queries.insertMediaQueue(
                        local_id = record.localId,
                        media_id = record.mediaId,
                        chat_id = record.chatId,
                        encrypted_manifest = encrypted,
                        state = record.state.name,
                        retry_count = record.retryCount.toLong(),
                        created_at_epoch_ms = record.createdAtEpochMillis,
                    )
                } else {
                    check(existing.chat_id == record.chatId)
                    check(existing.created_at_epoch_ms == record.createdAtEpochMillis)
                    queries.updateMediaQueue(
                        media_id = record.mediaId,
                        encrypted_manifest = encrypted,
                        state = record.state.name,
                        retry_count = record.retryCount.toLong(),
                        local_id = record.localId,
                    )
                }
            }
        }
    }

    override suspend fun get(localId: String): MediaQueueRecord? = mutex.withLock {
        queries.selectMediaQueueById(localId).executeAsOneOrNull()?.let {
            decode(key(), it.encrypted_manifest).also { record -> validateIndexes(record, it) }
        }
    }

    override suspend fun pending(): List<MediaQueueRecord> = mutex.withLock {
        val key = key()
        queries.selectPendingMediaQueue().executeAsList().map {
            decode(key, it.encrypted_manifest).also { record -> validateIndexes(record, it) }
        }
    }

    override suspend fun delete(localId: String) {
        mutex.withLock {
            queries.deleteMediaQueue(localId)
        }
    }

    override suspend fun clear() = mutex.withLock {
        queries.deleteAllMediaQueue()
        try {
            secureStorage.delete(KEY_NAME)
        } finally {
            keyInMemory?.fill(0)
            keyInMemory = null
        }
    }

    private suspend fun key(): ByteArray {
        keyInMemory?.let { return it }
        val stored = secureStorage.read(KEY_NAME)
        val key = try {
            when {
                stored == null -> LocalCacheCrypto.generateKey().also {
                    secureStorage.write(KEY_NAME, it)
                }
                stored.size == LocalCacheCrypto.KEY_BYTES -> stored.copyOf()
                else -> error("protected media queue key is malformed")
            }
        } finally {
            stored?.fill(0)
        }
        keyInMemory = key
        return key
    }

    private fun decode(key: ByteArray, ciphertext: ByteArray): MediaQueueRecord {
        val plaintext = LocalCacheCrypto.decrypt(key, ciphertext)
        return try {
            json.decodeFromString(plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateIndexes(
        record: MediaQueueRecord,
        row: com.tima.client.database.Media_queue,
    ) {
        check(record.localId == row.local_id)
        check(record.chatId == row.chat_id)
        check(record.mediaId == row.media_id)
        check(record.state.name == row.state)
        check(record.retryCount.toLong() == row.retry_count)
        check(record.createdAtEpochMillis == row.created_at_epoch_ms)
    }

    private companion object {
        const val KEY_NAME = "media-queue-row-key-v1"
        val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
        }
    }
}
