package com.tima.client.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tima.client.database.TimaDatabase
import com.tima.client.media.MediaQueueRecord
import com.tima.client.media.MediaQueueState
import com.tima.client.media.MediaMessageBinding
import com.tima.client.media.MediaVariantName
import com.tima.client.media.PresignedUploadSlot
import com.tima.client.media.QueuedMediaVariant
import com.tima.client.network.SecureStorage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EncryptedMediaQueueStoreTest {
    @Test
    fun keysAndPresignedUrlsAreEncryptedAndSurviveRestart() = runBlocking {
        val path = Files.createTempFile("tima-media-queue", ".sqlite")
        val storage = TestStorage()
        val record = MediaQueueRecord(
            localId = "00000000-0000-4000-8000-000000000001",
            chatId = "00000000-0000-4000-8000-000000000002",
            secretRef = "media:test",
            messageBinding = MediaMessageBinding(
                "00000000-0000-4000-8000-000000000006",
                "00000000-0000-4000-8000-000000000007",
                "00000000-0000-4000-8000-000000000008",
            ),
            initIdempotencyKey = "00000000-0000-4000-8000-000000000003",
            completeIdempotencyKey = "00000000-0000-4000-8000-000000000004",
            variants = MediaVariantName.entries.map {
                QueuedMediaVariant(it, "cipher-${it.wireValue}", ByteArray(32) { 0x5a }, "ab".repeat(32), 100, 1, 1)
            },
            mediaId = "00000000-0000-4000-8000-000000000005",
            slots = MediaVariantName.entries.map {
                PresignedUploadSlot(
                    it,
                    "https://private.example/KNOWN-PRESIGNED/${it.wireValue}",
                    emptyMap(),
                    2_000L,
                )
            },
            uploadExpiresAtEpochMillis = 2_000L,
            state = MediaQueueState.RETRY,
            createdAtEpochMillis = 1_000L,
        )
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            TimaDatabase.Schema.create(driver)
            EncryptedSqlDelightMediaQueueStore(TimaDatabase(driver), storage).put(record)
        }
        val bytes = Files.readAllBytes(path)
        assertFalse(bytes.containsBytes("KNOWN-PRESIGNED".encodeToByteArray()))
        assertFalse(bytes.containsBytes(ByteArray(32) { 0x5a }))

        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            val store = EncryptedSqlDelightMediaQueueStore(TimaDatabase(driver), storage)
            val restored = store.get(record.localId)
            assertEquals(record.mediaId, restored?.mediaId)
            assertContentEquals(record.variants.first().key, restored?.variants?.first()?.key)
            store.clear()
        }
        assertNull(storage.read("media-queue-row-key-v1"))
        Files.deleteIfExists(path)
        Unit
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && needle.size <= size &&
            (0..size - needle.size).any { start ->
                needle.indices.all { this[start + it] == needle[it] }
            }

    private class TestStorage : SecureStorage {
        private val values = mutableMapOf<String, ByteArray>()
        override suspend fun read(key: String) = values[key]?.copyOf()
        override suspend fun write(key: String, value: ByteArray) { values[key] = value.copyOf() }
        override suspend fun delete(key: String) { values.remove(key)?.fill(0) }
    }
}
