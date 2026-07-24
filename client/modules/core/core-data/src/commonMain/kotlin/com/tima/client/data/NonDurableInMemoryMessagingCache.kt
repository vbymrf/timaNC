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
    private val outboxValues = mutableMapOf<String, DurableSend>()

    override suspend fun chats(): List<ChatPreview> = mutex.withLock { chatValues }

    override suspend fun replaceChats(value: List<ChatPreview>) {
        mutex.withLock { chatValues = value.toList() }
    }

    override suspend fun messages(chatId: String): List<MessageBubble> =
        mutex.withLock { messageValues[chatId].orEmpty() }

    override suspend fun replaceRemoteMessages(chatId: String, value: List<MessageBubble>) {
        mutex.withLock {
            val local = messageValues[chatId].orEmpty()
                .filter {
                    it.messageId == null ||
                        it.delivery == MessageDeliveryState.PENDING ||
                        it.delivery == MessageDeliveryState.SENDING ||
                        it.delivery == MessageDeliveryState.ERROR
                }
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

    override suspend fun enqueueSend(value: DurableSend, bubble: MessageBubble) {
        mutex.withLock {
            upsertMessageLocked(bubble)
            outboxValues[value.localId] = value.copy(envelope = value.envelope.copyOf())
        }
    }

    override suspend fun outboxSend(localId: String): DurableSend? = mutex.withLock {
        outboxValues[localId]?.copy(envelope = outboxValues.getValue(localId).envelope.copyOf())
    }

    override suspend fun dueOutboxSends(nowEpochMillis: Long, limit: Long): List<DurableSend> =
        mutex.withLock {
            val due = mutableListOf<DurableSend>()
            val maximum = limit.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            for (value in outboxValues.values) {
                if (
                    due.size < maximum &&
                    (value.state == "pending" || value.state == "retry") &&
                    value.nextAttemptEpochMillis <= nowEpochMillis
                ) {
                    due += value.copy(envelope = value.envelope.copyOf())
                }
            }
            due
        }

    override suspend fun recoverSendingOutbox(nowEpochMillis: Long) {
        mutex.withLock {
            outboxValues.keys.toList().forEach { localId ->
                val value = outboxValues.getValue(localId)
                if (value.state == "sending") {
                    outboxValues[localId] =
                        value.copy(state = "retry", nextAttemptEpochMillis = nowEpochMillis)
                }
            }
        }
    }

    override suspend fun claimOutboxSend(localId: String): Boolean = mutex.withLock {
        val value = outboxValues[localId] ?: return@withLock false
        if (value.state !in setOf("pending", "retry")) return@withLock false
        outboxValues[localId] = value.copy(state = "sending")
        true
    }

    override suspend fun scheduleOutboxRetry(localId: String, nextAttemptEpochMillis: Long) {
        mutex.withLock {
            val value = outboxValues[localId] ?: return@withLock
            if (value.state == "sending") {
                outboxValues[localId] = value.copy(
                    state = "retry",
                    retryCount = value.retryCount + 1,
                    nextAttemptEpochMillis = nextAttemptEpochMillis,
                )
            }
        }
    }

    override suspend fun makeOutboxSendDue(localId: String, nowEpochMillis: Long) {
        mutex.withLock {
            val value = outboxValues[localId] ?: return@withLock
            if (value.state in setOf("pending", "retry", "terminal")) {
                outboxValues[localId] = value.copy(state = "pending", nextAttemptEpochMillis = nowEpochMillis)
            }
        }
    }

    override suspend fun terminallyFailOutboxSend(localId: String, bubble: MessageBubble?) {
        mutex.withLock {
            outboxValues[localId]?.let { value ->
                if (value.state == "sending") outboxValues[localId] = value.copy(state = "terminal")
            }
            bubble?.let(::upsertMessageLocked)
        }
    }

    override suspend fun completeOutboxSend(localId: String, bubble: MessageBubble) {
        mutex.withLock {
            upsertMessageLocked(bubble)
            outboxValues.remove(localId)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            chatValues = emptyList()
            messageValues.clear()
            outboxValues.clear()
        }
    }

    private fun upsertMessageLocked(value: MessageBubble) {
        val current = messageValues[value.chatId].orEmpty()
        messageValues[value.chatId] = current
            .filterNot {
                it.localId == value.localId ||
                    (value.messageId != null && it.messageId == value.messageId)
            }
            .plus(value)
    }
}
