package com.tima.client.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic process-memory cache for UI integration and tests.
 *
 * It is intentionally non-durable: app termination loses all content. Native applications must
 * replace it with a protected platform-backed implementation before claiming offline persistence.
 */
class NonDurableInMemoryMessagingCache : MessagingCache {
    private val mutex = Mutex()
    private var chatValues = emptyList<ChatPreview>()
    private val messageValues = mutableMapOf<String, List<MessageBubble>>()

    override suspend fun chats(): List<ChatPreview> = mutex.withLock { chatValues }

    override suspend fun replaceChats(value: List<ChatPreview>) {
        mutex.withLock { chatValues = value.toList() }
    }

    override suspend fun messages(chatId: String): List<MessageBubble> =
        mutex.withLock { messageValues[chatId].orEmpty() }

    override suspend fun replaceRemoteMessages(chatId: String, value: List<MessageBubble>) {
        mutex.withLock {
            val local = messageValues[chatId].orEmpty()
                .filter { it.messageId == null || it.delivery == MessageDeliveryState.ERROR }
            messageValues[chatId] = (value + local).distinctBy(MessageBubble::localId)
        }
    }

    override suspend fun upsertMessage(value: MessageBubble) {
        mutex.withLock {
            val current = messageValues[value.chatId].orEmpty()
            messageValues[value.chatId] = current
                .filterNot {
                    it.localId == value.localId ||
                        (value.messageId != null && it.messageId == value.messageId)
                }
                .plus(value)
        }
    }

    override suspend fun removeMessage(chatId: String, messageId: ULong) {
        mutex.withLock {
            messageValues[chatId] = messageValues[chatId].orEmpty()
                .filterNot { it.messageId == messageId }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            chatValues = emptyList()
            messageValues.clear()
        }
    }
}
