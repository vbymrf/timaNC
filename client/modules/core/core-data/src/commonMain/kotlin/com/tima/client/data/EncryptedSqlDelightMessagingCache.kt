package com.tima.client.data

import com.tima.client.crypto.LocalCacheCrypto
import com.tima.client.database.TimaDatabase
import com.tima.client.network.SecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable UI cache with row-level authenticated encryption.
 *
 * Identifiers and ordering fields remain queryable. Complete model payloads,
 * including message text and peer display names, are encrypted before SQLite.
 * The same database also owns the ciphertext-only send outbox, allowing bubble and outbox
 * transitions to commit atomically.
 */
class EncryptedSqlDelightMessagingCache(
    database: TimaDatabase,
    private val secureStorage: SecureStorage,
) : MessagingCache {
    private val queries = database.phase1Queries
    private val mutex = Mutex()
    private var keyInMemory: ByteArray? = null

    override suspend fun chats(): List<ChatPreview> = mutex.withLock {
        val key = cacheKey()
        queries.selectCachedChats().executeAsList().map { row ->
            decode<ChatPreview>(key, row.ciphertext).also {
                check(it.chatId == row.chat_id) {
                    "encrypted chat cache indexes do not match payload"
                }
            }
        }
    }

    override suspend fun replaceChats(value: List<ChatPreview>) {
        mutex.withLock {
            val key = cacheKey()
            val encrypted = value.map { encode(key, it) }
            queries.transaction {
                queries.deleteCachedChats()
                value.forEachIndexed { index, chat ->
                    queries.insertCachedChat(
                        chat_id = chat.chatId,
                        position = index.toLong(),
                        ciphertext = encrypted[index],
                    )
                }
            }
        }
    }

    override suspend fun messages(chatId: String): List<MessageBubble> = mutex.withLock {
        readMessages(chatId, cacheKey())
    }

    override suspend fun replaceRemoteMessages(chatId: String, value: List<MessageBubble>) {
        mutex.withLock {
            val key = cacheKey()
            val local = readMessages(chatId, key)
                .filter {
                    it.messageId == null ||
                        it.delivery == MessageDeliveryState.PENDING ||
                        it.delivery == MessageDeliveryState.SENDING ||
                        it.delivery == MessageDeliveryState.ERROR
                }
            writeMessages(chatId, (value + local).distinctBy(MessageBubble::localId), key)
        }
    }

    override suspend fun upsertMessage(value: MessageBubble) {
        mutex.withLock {
            val key = cacheKey()
            val updated = readMessages(value.chatId, key)
                .filterNot {
                    it.localId == value.localId ||
                        (value.messageId != null && it.messageId == value.messageId)
                }
                .plus(value)
            writeMessages(value.chatId, updated, key)
        }
    }

    override suspend fun removeMessage(chatId: String, messageId: ULong) {
        mutex.withLock {
            val key = cacheKey()
            writeMessages(
                chatId,
                readMessages(chatId, key).filterNot { it.messageId == messageId },
                key,
            )
        }
    }

    override suspend fun enqueueSend(value: DurableSend, bubble: MessageBubble) {
        require(value.localId == bubble.localId && value.chatId == bubble.chatId) {
            "outbox and bubble identifiers differ"
        }
        mutex.withLock {
            val key = cacheKey()
            val messages = updatedMessages(bubble, key)
            val encrypted = messages.map { encode(key, it) }
            queries.transaction {
                writeMessagesPrepared(bubble.chatId, messages, encrypted)
                queries.insertOutbox(
                    local_id = value.localId,
                    idempotency_key = value.idempotencyKey,
                    chat_id = value.chatId,
                    request_path = value.requestPath,
                    envelope = value.envelope,
                    next_attempt_epoch_ms = value.nextAttemptEpochMillis,
                    created_at_epoch_ms = value.createdAtEpochMillis,
                )
            }
        }
    }

    override suspend fun outboxSend(localId: String): DurableSend? = mutex.withLock {
        queries.selectOutboxById(localId).executeAsOneOrNull()?.toDurableSend()
    }

    override suspend fun dueOutboxSends(nowEpochMillis: Long, limit: Long): List<DurableSend> =
        mutex.withLock {
            queries.selectDueOutbox(nowEpochMillis, limit).executeAsList().map { it.toDurableSend() }
        }

    override suspend fun recoverSendingOutbox(nowEpochMillis: Long) {
        mutex.withLock { queries.recoverSendingOutbox(nowEpochMillis) }
    }

    override suspend fun claimOutboxSend(localId: String): Boolean = mutex.withLock {
        queries.markOutboxSending(localId)
        queries.selectOutboxById(localId).executeAsOneOrNull()?.state == "sending"
    }

    override suspend fun scheduleOutboxRetry(localId: String, nextAttemptEpochMillis: Long) {
        mutex.withLock { queries.markOutboxRetry(nextAttemptEpochMillis, localId) }
    }

    override suspend fun makeOutboxSendDue(localId: String, nowEpochMillis: Long) {
        mutex.withLock { queries.markOutboxPending(nowEpochMillis, localId) }
    }

    override suspend fun terminallyFailOutboxSend(localId: String, bubble: MessageBubble?) {
        if (bubble == null) {
            mutex.withLock { queries.markOutboxTerminal(localId) }
        } else {
            updateBubbleAndOutbox(bubble) {
                queries.markOutboxTerminal(localId)
            }
        }
    }

    override suspend fun completeOutboxSend(localId: String, bubble: MessageBubble) {
        updateBubbleAndOutbox(bubble) {
            queries.deleteOutbox(localId)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            queries.transaction {
                queries.deleteCachedChats()
                queries.deleteAllCachedMessages()
                queries.deleteAllOutbox()
            }
            try {
                secureStorage.delete(CACHE_KEY_NAME)
            } finally {
                keyInMemory?.fill(0)
                keyInMemory = null
            }
        }
    }

    private suspend fun cacheKey(): ByteArray {
        keyInMemory?.let { return it }
        val stored = secureStorage.read(CACHE_KEY_NAME)
        val key = try {
            when {
                stored == null -> LocalCacheCrypto.generateKey().also {
                    secureStorage.write(CACHE_KEY_NAME, it)
                }
                stored.size == LocalCacheCrypto.KEY_BYTES -> stored.copyOf()
                else -> throw IllegalStateException("protected local cache key is malformed")
            }
        } finally {
            stored?.fill(0)
        }
        keyInMemory = key
        return key
    }

    private fun readMessages(chatId: String, key: ByteArray): List<MessageBubble> =
        queries.selectCachedMessages(chatId).executeAsList().map { row ->
            decode<MessageBubble>(key, row.ciphertext).also {
                check(
                    it.localId == row.local_id &&
                        it.chatId == row.chat_id &&
                        it.messageId?.toString() == row.message_id &&
                        it.delivery.name == row.delivery_state,
                ) { "encrypted message cache indexes do not match payload" }
            }
        }

    private fun writeMessages(chatId: String, values: List<MessageBubble>, key: ByteArray) {
        require(values.all { it.chatId == chatId }) { "message belongs to a different chat" }
        val encrypted = values.map { encode(key, it) }
        queries.transaction {
            writeMessagesPrepared(chatId, values, encrypted)
        }
    }

    private fun writeMessagesPrepared(
        chatId: String,
        values: List<MessageBubble>,
        encrypted: List<ByteArray>,
    ) {
        queries.deleteCachedMessagesForChat(chatId)
        values.forEachIndexed { index, message ->
            queries.insertCachedMessage(
                local_id = message.localId,
                chat_id = message.chatId,
                message_id = message.messageId?.toString(),
                delivery_state = message.delivery.name,
                position = index.toLong(),
                ciphertext = encrypted[index],
            )
        }
    }

    private fun updatedMessages(value: MessageBubble, key: ByteArray): List<MessageBubble> =
        readMessages(value.chatId, key)
            .filterNot {
                it.localId == value.localId ||
                    (value.messageId != null && it.messageId == value.messageId)
            }
            .plus(value)

    private suspend fun updateBubbleAndOutbox(
        bubble: MessageBubble,
        outboxTransition: () -> Unit,
    ) {
        mutex.withLock {
            val key = cacheKey()
            val messages = updatedMessages(bubble, key)
            val encrypted = messages.map { encode(key, it) }
            queries.transaction {
                writeMessagesPrepared(bubble.chatId, messages, encrypted)
                outboxTransition()
            }
        }
    }

    private fun com.tima.client.database.Sync_outbox.toDurableSend() = DurableSend(
        localId = local_id,
        idempotencyKey = idempotency_key,
        chatId = chat_id,
        requestPath = request_path,
        envelope = envelope.copyOf(),
        state = state,
        retryCount = retry_count,
        nextAttemptEpochMillis = next_attempt_epoch_ms,
        createdAtEpochMillis = created_at_epoch_ms,
    )

    private inline fun <reified T> encode(key: ByteArray, value: T): ByteArray {
        val plaintext = json.encodeToString(value).encodeToByteArray()
        return try {
            LocalCacheCrypto.encrypt(key, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private inline fun <reified T> decode(key: ByteArray, ciphertext: ByteArray): T {
        var plaintext: ByteArray? = null
        try {
            plaintext = LocalCacheCrypto.decrypt(key, ciphertext)
            return json.decodeFromString(plaintext.decodeToString())
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw IllegalStateException("encrypted local cache row is malformed or undecryptable", error)
        } finally {
            plaintext?.fill(0)
        }
    }

    private companion object {
        const val CACHE_KEY_NAME = "messaging-cache-row-key-v1"
        val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
        }
    }
}
