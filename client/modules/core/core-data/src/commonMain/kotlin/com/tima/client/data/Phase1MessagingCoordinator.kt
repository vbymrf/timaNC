package com.tima.client.data

import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.RestGapFill
import com.tima.client.network.ReservedMessageIds
import com.tima.client.network.TimaTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface Phase1MessagingRepository : RestGapFill {
    val state: StateFlow<MessagingUiState>
    suspend fun loadSession()
    suspend fun clearSessionState()
    suspend fun refreshChats()
    suspend fun createChat(peerUserId: String): ChatPreview
    suspend fun openThread(chatId: String)
    suspend fun sendText(chatId: String, text: String): String
    suspend fun retrySend(localId: String)
    suspend fun editText(chatId: String, messageId: ULong, text: String)
    suspend fun markRead(chatId: String, messageId: ULong)
    suspend fun deleteMessage(chatId: String, messageId: ULong)
}

/**
 * Shared Phase 1 state holder and transport-backed messaging orchestration.
 *
 * Durable offline behavior depends on the injected MessagingCache. The supplied
 * NonDurableInMemoryMessagingCache is only a process cache and never queues across restarts.
 */
class Phase1MessagingCoordinator(
    private val sessions: SessionRepository,
    private val remote: MessagingRemoteDataSource,
    private val crypto: PrivateMessageCrypto,
    private val cache: MessagingCache,
    private val ids: IdGenerator,
    private val connectivity: Connectivity = Connectivity { true },
) : Phase1MessagingRepository {
    private val mutableState = MutableStateFlow(MessagingUiState())
    override val state: StateFlow<MessagingUiState> = mutableState.asStateFlow()
    private val pending = mutableMapOf<String, PendingSend>()
    private val pendingMutex = Mutex()

    override suspend fun loadSession() {
        mutableState.value = try {
            sessions.current()?.let {
                mutableState.value.copy(session = SessionUiState.SignedIn(it.userId, it.deviceId))
            } ?: mutableState.value.copy(session = SessionUiState.SignedOut)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableState.value.copy(session = SessionUiState.Error("SECURE_STORAGE_UNAVAILABLE"))
        }
    }

    override suspend fun clearSessionState() {
        pendingMutex.withLock { pending.clear() }
        cache.clear()
        mutableState.value = MessagingUiState(session = SessionUiState.SignedOut)
    }

    override suspend fun refreshChats() {
        val cached = cache.chats()
        mutableState.value = mutableState.value.copy(chats = UiLoadState.Loading)
        if (!connectivity.isOnline()) {
            mutableState.value = mutableState.value.copy(
                chats = if (cached.isEmpty()) UiLoadState.Empty(offline = true)
                else UiLoadState.Content(cached, offline = true),
            )
            return
        }
        try {
            val chats = remote.listChats().map { it.toPreview() }
            cache.replaceChats(chats)
            mutableState.value = mutableState.value.copy(
                chats = if (chats.isEmpty()) UiLoadState.Empty() else UiLoadState.Content(chats),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                chats = error.toUiError(cached.takeIf { it.isNotEmpty() }),
            )
        }
    }

    override suspend fun createChat(peerUserId: String): ChatPreview {
        val created = remote.createChat(peerUserId, ids.next()).toPreview()
        cache.replaceChats(listOf(created) + cache.chats().filterNot { it.chatId == created.chatId })
        refreshChatsFromCache()
        return created
    }

    override suspend fun openThread(chatId: String) {
        val cached = cache.messages(chatId)
        mutableState.value = mutableState.value.copy(
            activeChatId = chatId,
            thread = UiLoadState.Loading,
        )
        if (!connectivity.isOnline()) {
            mutableState.value = mutableState.value.copy(
                thread = cached.toLoadState(offline = true),
            )
            return
        }
        try {
            refreshHistory(chatId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                thread = error.toUiError(cached.takeIf { it.isNotEmpty() }),
            )
        }
    }

    override suspend fun catchUp(chatIds: Set<String>) {
        val targets = if (chatIds.isEmpty()) {
            refreshChats()
            cache.chats().map(ChatPreview::chatId).toSet()
        } else {
            chatIds
        }
        targets.forEach { refreshHistory(it) }
    }

    override suspend fun sendText(chatId: String, text: String): String {
        require(text.isNotBlank()) { "message text must not be blank" }
        val session = requireNotNull(sessions.current()) { "authenticated session required" }
        val localId = ids.next()
        val operation = PendingSend(
            localId = localId,
            chatId = chatId,
            senderUserId = session.userId,
            text = text,
            reserveIdempotencyKey = ids.next(),
            sendIdempotencyKey = ids.next(),
        )
        pendingMutex.withLock { pending[localId] = operation }
        cache.upsertMessage(operation.bubble(MessageDeliveryState.PENDING))
        showCachedThread(chatId)
        executeSend(operation)
        return localId
    }

    override suspend fun retrySend(localId: String) {
        val operation = requireNotNull(pendingMutex.withLock { pending[localId] }) {
            "pending send not found"
        }
        executeSend(operation)
    }

    override suspend fun editText(chatId: String, messageId: ULong, text: String) {
        require(text.isNotBlank())
        val current = cache.messages(chatId).singleOrNull { it.messageId == messageId }
            ?: error("message not found in local cache")
        val revisionNumber = current.revisionNumber + 1uL
        val revisionId = ids.next()
        val encrypted = crypto.encrypt(
            chatId = chatId,
            text = text,
            reservation = ReservedMessageIds(messageId, revisionId),
            parentRevisionId = requireNotNull(current.revisionId) {
                "server revision is required before editing"
            },
            revisionNumber = revisionNumber,
        )
        remote.edit(chatId, messageId, encrypted, ids.next())
        cache.upsertMessage(
            current.copy(
                localId = "remote:$chatId:$messageId:$revisionId",
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                text = text,
                edited = true,
                delivery = MessageDeliveryState.SENT,
                errorCode = null,
            ),
        )
        showCachedThread(chatId)
    }

    override suspend fun markRead(chatId: String, messageId: ULong) {
        remote.markRead(chatId, messageId)
        val session = sessions.current()
        cache.messages(chatId)
            .filter { it.senderUserId != session?.userId && it.messageId != null && it.messageId <= messageId }
            .forEach { cache.upsertMessage(it.copy(delivery = MessageDeliveryState.READ)) }
        showCachedThread(chatId)
    }

    override suspend fun deleteMessage(chatId: String, messageId: ULong) {
        remote.delete(chatId, messageId)
        cache.removeMessage(chatId, messageId)
        showCachedThread(chatId)
    }

    private suspend fun executeSend(operation: PendingSend) {
        cache.upsertMessage(operation.bubble(MessageDeliveryState.SENDING))
        mutableState.value = mutableState.value.copy(send = SendUiState.Sending(operation.localId))
        showCachedThread(operation.chatId)
        try {
            val reservation = operation.reservation ?: remote.reserveMessage(
                operation.chatId,
                operation.reserveIdempotencyKey,
            ).also { operation.reservation = it }
            val encrypted = operation.encrypted ?: crypto.encrypt(
                operation.chatId,
                operation.text,
                reservation,
            ).also { operation.encrypted = it }
            remote.send(operation.chatId, encrypted, operation.sendIdempotencyKey)
            cache.upsertMessage(operation.bubble(MessageDeliveryState.SENT, reservation.messageId))
            pendingMutex.withLock { pending.remove(operation.localId) }
            mutableState.value = mutableState.value.copy(
                send = SendUiState.Sent(operation.localId, reservation.messageId),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failure = error.failure()
            cache.upsertMessage(
                operation.bubble(MessageDeliveryState.ERROR).copy(errorCode = failure.code),
            )
            mutableState.value = mutableState.value.copy(
                send = SendUiState.Error(operation.localId, failure.code, failure.retryable),
            )
        }
        showCachedThread(operation.chatId)
    }

    private suspend fun refreshHistory(chatId: String) {
        val session = requireNotNull(sessions.current()) { "authenticated session required" }
        val all = mutableListOf<com.tima.client.network.PrivateMessageHistoryDto>()
        var cursor: String? = null
        do {
            val page = remote.history(chatId, cursor)
            all += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        val messages = all.sortedBy { it.id.toULong() }.map { value ->
            val plain = crypto.decrypt(value)
            MessageBubble(
                localId = "remote:$chatId:${value.id}:${value.current_revision_id}",
                chatId = chatId,
                messageId = value.id.toULong(),
                revisionId = value.current_revision_id,
                revisionNumber = plain.revisionNumber,
                senderUserId = value.sender_id,
                text = plain.text,
                createdAt = value.created_at,
                edited = plain.revisionNumber > 1uL,
                delivery = MessageDeliveryState.SENT,
            )
        }
        cache.replaceRemoteMessages(chatId, messages)
        if (state.value.activeChatId == chatId) showCachedThread(chatId)
        if (messages.any { it.senderUserId != session.userId }) {
            val previews = cache.chats().map {
                if (it.chatId == chatId) it.copy(unreadCount = it.unreadCount) else it
            }
            cache.replaceChats(previews)
        }
    }

    private suspend fun showCachedThread(chatId: String) {
        if (mutableState.value.activeChatId == chatId) {
            mutableState.value = mutableState.value.copy(thread = cache.messages(chatId).toLoadState())
        }
    }

    private suspend fun refreshChatsFromCache() {
        val values = cache.chats()
        mutableState.value = mutableState.value.copy(chats = values.toLoadState())
    }

    private fun RemoteChat.toPreview() = ChatPreview(
        chatId = id,
        peerUserId = peerUserId,
        peerDisplayName = peerDisplayName,
        lastMessageAt = lastMessageAt,
        unreadCount = unreadCount,
    )

    private fun <T> List<T>.toLoadState(offline: Boolean = false): UiLoadState<List<T>> =
        if (isEmpty()) UiLoadState.Empty(offline) else UiLoadState.Content(this, offline)

    private fun <T> Throwable.toUiError(cached: T?): UiLoadState.Error<T> {
        val failure = failure()
        return UiLoadState.Error(
            code = failure.code,
            retryable = failure.retryable,
            cached = cached,
            offline = !connectivity.isOnline(),
        )
    }

    private fun Throwable.failure(): Failure = when (this) {
        is TimaTransportException -> Failure(code, retryable)
        is MissingEncryptionConfigurationException -> Failure("ENCRYPTION_CONFIGURATION_MISSING", false)
        else -> Failure("CLIENT_OPERATION_FAILED", true)
    }

    private data class Failure(val code: String, val retryable: Boolean)

    private data class PendingSend(
        val localId: String,
        val chatId: String,
        val senderUserId: String,
        val text: String,
        val reserveIdempotencyKey: String,
        val sendIdempotencyKey: String,
        var reservation: ReservedMessageIds? = null,
        var encrypted: PrivateMessageWriteDto? = null,
    ) {
        fun bubble(state: MessageDeliveryState, messageId: ULong? = reservation?.messageId) =
            MessageBubble(
                localId = localId,
                chatId = chatId,
                messageId = messageId,
                revisionId = reservation?.revisionId,
                revisionNumber = 1uL,
                senderUserId = senderUserId,
                text = text,
                createdAt = null,
                delivery = state,
            )
    }
}
