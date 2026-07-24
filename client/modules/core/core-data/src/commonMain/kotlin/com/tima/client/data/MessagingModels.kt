package com.tima.client.data

import com.tima.client.media.MediaAttachmentUi
import kotlinx.serialization.Serializable

/**
 * UI-facing load state. Offline is explicit; cache durability depends on the injected runtime.
 */
sealed interface UiLoadState<out T> {
    data object Loading : UiLoadState<Nothing>
    data class Empty(val offline: Boolean = false) : UiLoadState<Nothing>
    data class Content<T>(val value: T, val offline: Boolean = false) : UiLoadState<T>
    data class Error<T>(
        val code: String,
        val retryable: Boolean,
        val cached: T? = null,
        val offline: Boolean = false,
    ) : UiLoadState<T>
}

data class ClientSession(
    val accessToken: String,
    val userId: String,
    val deviceId: String,
)

sealed interface SessionUiState {
    data object Loading : SessionUiState
    data object SignedOut : SessionUiState
    data class SignedIn(val userId: String, val deviceId: String) : SessionUiState
    data class Error(val code: String) : SessionUiState
}

@Serializable
data class ChatPreview(
    val chatId: String,
    val peerUserId: String,
    val peerDisplayName: String,
    val lastMessageAt: String?,
    val unreadCount: Int,
)

@Serializable
enum class MessageDeliveryState {
    PENDING,
    SENDING,
    SENT,
    READ,
    ERROR,
}

@Serializable
data class MessageBubble(
    val localId: String,
    val chatId: String,
    val messageId: ULong?,
    val revisionId: String?,
    val revisionNumber: ULong,
    val senderUserId: String,
    val text: String,
    val createdAt: String?,
    val edited: Boolean = false,
    val delivery: MessageDeliveryState,
    val errorCode: String? = null,
    val attachment: MediaAttachmentUi? = null,
)

sealed interface SendUiState {
    data object Idle : SendUiState
    data class Sending(val localId: String) : SendUiState
    data class Sent(val localId: String, val messageId: ULong) : SendUiState
    data class Error(val localId: String, val code: String, val retryable: Boolean) : SendUiState
}

data class MessagingUiState(
    val session: SessionUiState = SessionUiState.Loading,
    val chats: UiLoadState<List<ChatPreview>> = UiLoadState.Loading,
    val activeChatId: String? = null,
    val thread: UiLoadState<List<MessageBubble>> = UiLoadState.Empty(),
    val send: SendUiState = SendUiState.Idle,
)
