package com.tima.client.data

import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.PlainTextDocumentV2
import com.tima.client.media.MediaAttachmentUi
import com.tima.client.media.MediaMessageBinding
import com.tima.client.media.MediaTransferException
import com.tima.client.media.PrivateImageDocument
import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.PrivateMessageWriteCodec
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
    suspend fun loadSession(drainOutbox: Boolean = true)
    suspend fun clearSessionState()
    suspend fun refreshChats()
    suspend fun createChat(peerUserId: String): ChatPreview
    suspend fun openThread(chatId: String)
    suspend fun sendText(chatId: String, text: String): String
    suspend fun sendMedia(chatId: String, attachment: MediaAttachmentUi): String
    suspend fun ensureMediaMessage(
        chatId: String,
        attachment: MediaAttachmentUi,
        binding: MediaMessageBinding,
    )
    suspend fun retrySend(localId: String)
    suspend fun editText(chatId: String, messageId: ULong, text: String)
    suspend fun markRead(chatId: String, messageId: ULong)
    suspend fun deleteMessage(chatId: String, messageId: ULong)
}

/**
 * Shared Phase 1 state holder and transport-backed messaging orchestration.
 *
 * Reservation and encryption require a live process. Immediately after both succeed, the exact
 * canonical ciphertext request and send idempotency key are committed to the durable cache/outbox
 * before transport is attempted. No private plaintext is written to the outbox.
 */
class Phase1MessagingCoordinator(
    private val sessions: SessionRepository,
    private val remote: MessagingRemoteDataSource,
    private val crypto: PrivateMessageCrypto,
    private val cache: MessagingCache,
    private val ids: IdGenerator,
    private val connectivity: Connectivity = Connectivity { true },
    private val nowEpochMillis: () -> Long,
    private val baseRetryMillis: Long = 1_000,
    private val maxRetryMillis: Long = 60_000,
) : Phase1MessagingRepository {
    init {
        require(baseRetryMillis >= 0 && maxRetryMillis >= baseRetryMillis) {
            "retry delays must be non-negative and ordered"
        }
    }

    private val mutableState = MutableStateFlow(MessagingUiState())
    override val state: StateFlow<MessagingUiState> = mutableState.asStateFlow()
    private val preDurable = mutableMapOf<String, PendingSend>()
    private val pendingMutex = Mutex()
    private val drainMutex = Mutex()

    override suspend fun loadSession(drainOutbox: Boolean) {
        val session = try {
            sessions.current()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                session = SessionUiState.Error("SECURE_STORAGE_UNAVAILABLE"),
            )
            return
        }
        mutableState.value = session?.let {
                mutableState.value.copy(session = SessionUiState.SignedIn(it.userId, it.deviceId))
            } ?: mutableState.value.copy(session = SessionUiState.SignedOut)
        if (session != null && drainOutbox) {
            drainDue(recoverStale = true)
        }
    }

    override suspend fun clearSessionState() {
        pendingMutex.withLock { preDurable.clear() }
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
        } catch (error: Exception) {
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
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                thread = error.toUiError(cached.takeIf { it.isNotEmpty() }),
            )
        }
    }

    override suspend fun catchUp(chatIds: Set<String>) {
        if (sessions.current() == null) return
        drainDue(recoverStale = true)
        val targets = if (chatIds.isEmpty()) {
            refreshChats()
            cache.chats().map(ChatPreview::chatId).toSet()
        } else {
            chatIds
        }
        targets.forEach { chatId ->
            try {
                refreshHistory(chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (mutableState.value.activeChatId == chatId) {
                    val cached = cache.messages(chatId)
                    mutableState.value = mutableState.value.copy(
                        thread = error.toUiError(cached.takeIf { it.isNotEmpty() }),
                    )
                }
            }
        }
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
        pendingMutex.withLock { preDurable[localId] = operation }
        cache.upsertMessage(operation.bubble(MessageDeliveryState.PENDING))
        showCachedThread(chatId)
        prepareAndEnqueue(operation)
        return localId
    }

    override suspend fun sendMedia(chatId: String, attachment: MediaAttachmentUi): String {
        val binding = MediaMessageBinding(ids.next(), ids.next(), ids.next())
        ensureMediaMessage(chatId, attachment, binding)
        return binding.localId
    }

    override suspend fun ensureMediaMessage(
        chatId: String,
        attachment: MediaAttachmentUi,
        binding: MediaMessageBinding,
    ) {
        val operation = PendingSend(
            localId = binding.localId,
            chatId = chatId,
            senderUserId = requireNotNull(sessions.current()).userId,
            text = "",
            reserveIdempotencyKey = binding.reservationIdempotencyKey,
            sendIdempotencyKey = binding.sendIdempotencyKey,
            attachment = attachment,
        )
        cache.outboxSend(binding.localId)?.let {
            check(it.chatId == chatId && it.idempotencyKey == binding.sendIdempotencyKey) {
                "media binding collides with another durable outbox row"
            }
            return
        }
        cache.messages(chatId).singleOrNull { it.localId == binding.localId }?.let {
            check(it.attachment == attachment) { "media binding collides with another cached message" }
            if (it.delivery == MessageDeliveryState.SENT || it.delivery == MessageDeliveryState.READ) return
        }
        val stable = pendingMutex.withLock {
            preDurable[binding.localId]?.also {
                check(it.chatId == chatId && it.attachment == attachment)
            } ?: operation.also { preDurable[binding.localId] = it }
        }
        cache.upsertMessage(stable.bubble(MessageDeliveryState.PENDING))
        showCachedThread(chatId)
        prepareAndEnqueue(stable)
        val sent = mutableState.value.send as? SendUiState.Sent
        if (cache.outboxSend(binding.localId) == null && sent?.localId != binding.localId) {
            val failure = mutableState.value.send as? SendUiState.Error
            throw MediaTransferException(
                code = failure?.code ?: "MEDIA_MESSAGE_NOT_DURABLE",
                retryable = failure?.retryable == true,
            )
        }
    }

    override suspend fun retrySend(localId: String) {
        requireNotNull(sessions.current()) { "authenticated session required" }
        val durable = cache.outboxSend(localId)
        if (durable != null) {
            cache.makeOutboxSendDue(localId, nowEpochMillis())
            drainOne(localId)
            return
        }
        val operation = requireNotNull(pendingMutex.withLock { preDurable[localId] }) {
            "send operation not found"
        }
        prepareAndEnqueue(operation)
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

    private suspend fun prepareAndEnqueue(operation: PendingSend) {
        cache.upsertMessage(operation.bubble(MessageDeliveryState.SENDING))
        mutableState.value = mutableState.value.copy(send = SendUiState.Sending(operation.localId))
        showCachedThread(operation.chatId)
        try {
            val reservation = operation.reservation ?: remote.reserveMessage(
                operation.chatId,
                operation.reserveIdempotencyKey,
            ).also { operation.reservation = it }
            val encrypted = operation.encrypted ?: if (operation.attachment == null) {
                crypto.encrypt(operation.chatId, operation.text, reservation)
            } else {
                crypto.encryptDocument(
                    operation.chatId,
                    PlainTextDocumentV2(
                        markup = PrivateImageDocument.markup(
                            operation.attachment.mediaId,
                            operation.attachment.secretRef,
                        ),
                        secretMetadata = PrivateImageDocument.secretMetadata(
                            operation.attachment.secretRef,
                            operation.attachment,
                        ),
                        metadata = DocumentMetadata(revisionNumber = 1uL),
                    ),
                    reservation,
                )
            }.also { operation.encrypted = it }
            val envelope = PrivateMessageWriteCodec.encode(encrypted)
            val now = nowEpochMillis()
            cache.enqueueSend(
                DurableSend(
                    localId = operation.localId,
                    idempotencyKey = operation.sendIdempotencyKey,
                    chatId = operation.chatId,
                    requestPath = sendPath(operation.chatId),
                    envelope = envelope,
                    nextAttemptEpochMillis = now,
                    createdAtEpochMillis = now,
                ),
                operation.bubble(MessageDeliveryState.PENDING, reservation.messageId),
            )
            pendingMutex.withLock { preDurable.remove(operation.localId) }
            drainOne(operation.localId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (cache.outboxSend(operation.localId) != null) throw error
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

    private suspend fun drainDue(recoverStale: Boolean) {
        requireNotNull(sessions.current()) { "authenticated session required" }
        drainMutex.withLock {
            val now = nowEpochMillis()
            if (recoverStale) cache.recoverSendingOutbox(now)
            cache.dueOutboxSends(now).forEach { drainOneLocked(it.localId) }
        }
    }

    private suspend fun drainOne(localId: String) {
        requireNotNull(sessions.current()) { "authenticated session required" }
        drainMutex.withLock { drainOneLocked(localId) }
    }

    private suspend fun drainOneLocked(localId: String) {
        if (!cache.claimOutboxSend(localId)) return
        val item = requireNotNull(cache.outboxSend(localId)) { "claimed outbox send disappeared" }
        val bubble = cache.messages(item.chatId).singleOrNull { it.localId == localId }
        try {
            val value = validateOutbox(item, bubble)
            val sendingBubble = requireNotNull(bubble).copy(
                messageId = value.message_id.toULong(),
                revisionId = value.revision_id,
                delivery = MessageDeliveryState.SENDING,
                errorCode = null,
            )
            cache.upsertMessage(sendingBubble)
            mutableState.value = mutableState.value.copy(send = SendUiState.Sending(localId))
            showCachedThread(item.chatId)
            remote.sendSerialized(item.chatId, item.envelope, item.idempotencyKey)
            val sentBubble = sendingBubble.copy(delivery = MessageDeliveryState.SENT)
            cache.completeOutboxSend(localId, sentBubble)
            mutableState.value = mutableState.value.copy(
                send = SendUiState.Sent(localId, requireNotNull(sentBubble.messageId)),
            )
        } catch (cancelled: CancellationException) {
            cache.recoverSendingOutbox(nowEpochMillis())
            throw cancelled
        } catch (error: Exception) {
            val failure = if (error is OutboxValidationException) {
                Failure("OUTBOX_RECORD_INVALID", false)
            } else {
                error.failure()
            }
            val failedBubble = bubble?.copy(
                delivery = MessageDeliveryState.ERROR,
                errorCode = failure.code,
            )
            if (failure.retryable) {
                cache.scheduleOutboxRetry(
                    localId,
                    safeAdd(nowEpochMillis(), retryDelay(item.retryCount)),
                )
                failedBubble?.let { cache.upsertMessage(it) }
            } else {
                cache.terminallyFailOutboxSend(localId, failedBubble)
            }
            mutableState.value = mutableState.value.copy(
                send = SendUiState.Error(localId, failure.code, failure.retryable),
            )
        }
        showCachedThread(item.chatId)
    }

    private fun validateOutbox(item: DurableSend, bubble: MessageBubble?): PrivateMessageWriteDto {
        try {
            requireCanonicalUuid(item.chatId, "outbox chat_id")
            requireCanonicalUuid(item.idempotencyKey, "outbox idempotency_key")
            require(item.requestPath == sendPath(item.chatId)) { "outbox path does not match chat" }
            require(item.requestPath.startsWith("/") && !item.requestPath.startsWith("//")) {
                "outbox path is not host-relative"
            }
            val value = PrivateMessageWriteCodec.decode(item.envelope)
            require(bubble != null) { "outbox bubble is missing" }
            require(value.message_id.toULong() == bubble.messageId) { "message id differs from bubble" }
            require(value.revision_id == bubble.revisionId) { "revision id differs from bubble" }
            return value
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw OutboxValidationException(error)
        }
    }

    private fun retryDelay(retryCount: Long): Long {
        var delay = baseRetryMillis.coerceAtLeast(0)
        repeat(retryCount.coerceIn(0, 62).toInt()) {
            delay = if (delay >= maxRetryMillis) maxRetryMillis
            else if (delay > maxRetryMillis / 2) maxRetryMillis
            else delay * 2
        }
        return delay
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (increment > 0 && value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun sendPath(chatId: String) = "/v1/chats/$chatId/messages"

    private fun requireCanonicalUuid(value: String, name: String) {
        require(value.length == 36 && value == value.lowercase()) { "$name must be a canonical UUID" }
        require(value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-') {
            "$name must be a canonical UUID"
        }
        require(value.filterNot { it == '-' }.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$name must be a canonical UUID"
        }
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
                attachment = plain.attachment,
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
    private class OutboxValidationException(cause: Throwable) :
        IllegalStateException("durable outbox row failed validation", cause)

    private data class PendingSend(
        val localId: String,
        val chatId: String,
        val senderUserId: String,
        val text: String,
        val reserveIdempotencyKey: String,
        val sendIdempotencyKey: String,
        val attachment: MediaAttachmentUi? = null,
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
                attachment = attachment,
                createdAt = null,
                delivery = state,
            )
    }
}
