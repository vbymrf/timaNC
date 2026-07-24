package com.tima.client.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tima.client.database.TimaDatabase
import com.tima.client.network.SecureStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EncryptedSqlDelightMessagingCacheTest {
    @Test
    fun encryptedRowsSurviveRestartAndPreserveReplacementSemantics() = runBlocking {
        val path = Files.createTempFile("tima-messaging-cache", ".sqlite")
        val storage = MemorySecureStorage()
        val chat = ChatPreview("chat-secret", "peer-id", "Known Peer Name", "2026-07-24T10:00:00Z", 2)
        val remote = bubble("remote-1", 10uL, "Known remote plaintext", MessageDeliveryState.SENT)
        val pending = bubble("pending-1", null, "Known pending plaintext", MessageDeliveryState.PENDING)
        val failed = bubble("failed-1", 11uL, "Known failed plaintext", MessageDeliveryState.ERROR)

        open(path, create = true).use { driver ->
            val cache = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            cache.replaceChats(listOf(chat))
            cache.upsertMessage(pending)
            cache.enqueueSend(
                DurableSend(
                    localId = pending.localId,
                    idempotencyKey = "00000000-0000-4000-8000-000000000090",
                    chatId = pending.chatId,
                    requestPath = "/v1/chats/${pending.chatId}/messages",
                    envelope = byteArrayOf(1, 2, 3),
                    nextAttemptEpochMillis = 1,
                    createdAtEpochMillis = 1,
                ),
                pending,
            )
            cache.upsertMessage(failed)
            cache.replaceRemoteMessages(chat.chatId, listOf(remote))
            assertEquals(listOf(remote, pending, failed), cache.messages(chat.chatId))
            assertEquals(pending.localId, cache.outboxSend(pending.localId)?.localId)
        }

        val databaseBytes = Files.readAllBytes(path)
        listOf(chat.peerDisplayName, remote.text, pending.text, failed.text).forEach {
            assertFalse(
                databaseBytes.containsSequence(it.encodeToByteArray()),
                "SQLite file contained known plaintext: $it",
            )
        }

        open(path).use { driver ->
            val cache = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            assertEquals(listOf(chat), cache.chats())
            assertEquals(listOf(remote, pending, failed), cache.messages(chat.chatId))

            val revised = remote.copy(localId = "remote-revised", text = "Revised secret")
            cache.upsertMessage(revised)
            assertEquals(listOf(pending, failed, revised), cache.messages(chat.chatId))
            cache.removeMessage(chat.chatId, 10uL)
            assertEquals(listOf(pending, failed), cache.messages(chat.chatId))
            cache.clear()
            assertNull(storage.read("messaging-cache-row-key-v1"))
        }

        open(path).use { driver ->
            val queries = TimaDatabase(driver).phase1Queries
            assertTrue(queries.selectCachedChats().executeAsList().isEmpty())
            assertTrue(queries.selectCachedMessages(chat.chatId).executeAsList().isEmpty())
            assertNull(queries.selectOutboxById(pending.localId).executeAsOneOrNull())
        }
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun malformedProtectedKeyFailsClosed() = runBlocking {
        val path = Files.createTempFile("tima-messaging-cache-bad-key", ".sqlite")
        val storage = MemorySecureStorage().apply {
            write("messaging-cache-row-key-v1", byteArrayOf(1, 2, 3))
        }
        open(path, create = true).use { driver ->
            val cache = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            assertFailsWith<IllegalStateException> { cache.chats() }
        }
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun replacedProtectedKeyCannotDecryptExistingRows() = runBlocking {
        val path = Files.createTempFile("tima-messaging-cache-wrong-key", ".sqlite")
        val storage = MemorySecureStorage()
        open(path, create = true).use { driver ->
            EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage).replaceChats(
                listOf(ChatPreview("chat-id", "peer-id", "Secret name", null, 0)),
            )
        }
        storage.write("messaging-cache-row-key-v1", ByteArray(32) { 7 })
        open(path).use { driver ->
            val cache = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            assertFailsWith<IllegalStateException> { cache.chats() }
        }
        Files.deleteIfExists(path)
        Unit
    }

    private fun open(path: Path, create: Boolean = false): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").also {
            if (create) TimaDatabase.Schema.create(it)
        }

    private fun bubble(
        localId: String,
        messageId: ULong?,
        text: String,
        delivery: MessageDeliveryState,
    ) = MessageBubble(
        localId = localId,
        chatId = "chat-secret",
        messageId = messageId,
        revisionId = messageId?.let { "revision-$it" },
        revisionNumber = 1uL,
        senderUserId = "sender-id",
        text = text,
        createdAt = "2026-07-24T10:00:00Z",
        delivery = delivery,
        errorCode = if (delivery == MessageDeliveryState.ERROR) "OFFLINE" else null,
    )
}

private fun ByteArray.containsSequence(value: ByteArray): Boolean {
    if (value.isEmpty() || value.size > size) return false
    return (0..size - value.size).any { offset ->
        value.indices.all { index -> this[offset + index] == value[index] }
    }
}

private class MemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = values[key]?.copyOf()

    override suspend fun write(key: String, value: ByteArray) {
        values[key] = value.copyOf()
    }

    override suspend fun delete(key: String) {
        values.remove(key)?.fill(0)
    }
}
