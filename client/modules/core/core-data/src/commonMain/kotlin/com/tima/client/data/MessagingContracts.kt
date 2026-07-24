package com.tima.client.data

import com.tima.client.network.PrivateMessageHistoryDto
import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.PrivateMessageWriteCodec
import com.tima.client.network.ReservedMessageIds

interface SessionRepository {
    suspend fun current(): ClientSession?
    suspend fun save(session: ClientSession)
    suspend fun clear()
}

data class RemoteChat(
    val id: String,
    val peerUserId: String,
    val peerDisplayName: String,
    val lastMessageAt: String?,
    val unreadCount: Int,
)

data class RemoteHistoryPage(
    val items: List<PrivateMessageHistoryDto>,
    val nextCursor: String?,
)

interface MessagingRemoteDataSource {
    suspend fun listChats(limit: Int = 50): List<RemoteChat>
    suspend fun createChat(peerUserId: String, idempotencyKey: String): RemoteChat
    suspend fun history(chatId: String, cursor: String? = null, limit: Int = 50): RemoteHistoryPage
    suspend fun reserveMessage(chatId: String, idempotencyKey: String): ReservedMessageIds
    suspend fun send(chatId: String, value: PrivateMessageWriteDto, idempotencyKey: String)
    suspend fun sendSerialized(chatId: String, envelope: ByteArray, idempotencyKey: String) {
        send(chatId, PrivateMessageWriteCodec.decode(envelope), idempotencyKey)
    }
    suspend fun edit(
        chatId: String,
        messageId: ULong,
        value: PrivateMessageWriteDto,
        idempotencyKey: String,
    )
    suspend fun markRead(chatId: String, messageId: ULong)
    suspend fun delete(chatId: String, messageId: ULong)
}

data class DecryptedMessage(
    val text: String,
    val revisionNumber: ULong,
)

interface PrivateMessageCrypto {
    suspend fun encrypt(
        chatId: String,
        text: String,
        reservation: ReservedMessageIds,
        parentRevisionId: String? = null,
        revisionNumber: ULong = 1uL,
    ): PrivateMessageWriteDto

    suspend fun decrypt(value: PrivateMessageHistoryDto): DecryptedMessage
}

/**
 * Decrypted-model cache boundary used by UI state. Implementations must document their durability
 * and platform protection. Tests may continue to use the process-memory implementation.
 */
interface MessagingCache {
    suspend fun chats(): List<ChatPreview>
    suspend fun replaceChats(value: List<ChatPreview>)
    suspend fun messages(chatId: String): List<MessageBubble>
    suspend fun replaceRemoteMessages(chatId: String, value: List<MessageBubble>)
    suspend fun upsertMessage(value: MessageBubble)
    suspend fun removeMessage(chatId: String, messageId: ULong)
    suspend fun enqueueSend(value: DurableSend, bubble: MessageBubble)
    suspend fun outboxSend(localId: String): DurableSend?
    suspend fun dueOutboxSends(nowEpochMillis: Long, limit: Long = 50): List<DurableSend>
    suspend fun recoverSendingOutbox(nowEpochMillis: Long)
    suspend fun claimOutboxSend(localId: String): Boolean
    suspend fun scheduleOutboxRetry(localId: String, nextAttemptEpochMillis: Long)
    suspend fun makeOutboxSendDue(localId: String, nowEpochMillis: Long)
    suspend fun terminallyFailOutboxSend(localId: String, bubble: MessageBubble?)
    suspend fun completeOutboxSend(localId: String, bubble: MessageBubble)
    suspend fun clear()
}

data class DurableSend(
    val localId: String,
    val idempotencyKey: String,
    val chatId: String,
    val requestPath: String,
    val envelope: ByteArray,
    val state: String = "pending",
    val retryCount: Long = 0,
    val nextAttemptEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

fun interface IdGenerator {
    fun next(): String
}

fun interface Connectivity {
    fun isOnline(): Boolean
}

class MissingEncryptionConfigurationException(
    val missing: Set<String>,
) : IllegalStateException(
    "private messaging encryption is blocked; missing verified configuration: ${missing.sorted().joinToString()}",
)
