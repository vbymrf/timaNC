package com.tima.client.windows

import com.tima.client.data.ChatPreview
import com.tima.client.data.MessageBubble
import com.tima.client.data.MessagingUiState
import com.tima.client.data.SessionUiState
import com.tima.client.data.UiLoadState

data class WindowsMessagingViewState(
    val sessionLabel: String,
    val signedIn: Boolean,
    val currentUserId: String?,
    val chats: List<ChatPreview>,
    val chatsStatus: String?,
    val messages: List<MessageBubble>,
    val threadStatus: String?,
    val activeChatId: String?,
    val sendEnabled: Boolean,
    val trustLabel: String,
    val deliveryBanner: String,
)

object WindowsMessagingPresenter {
    fun present(
        state: MessagingUiState,
        privateSendingEnabled: Boolean,
        trustSummary: String,
        deliverySummary: String,
    ): WindowsMessagingViewState {
        val signedIn = state.session is SessionUiState.SignedIn
        return WindowsMessagingViewState(
            sessionLabel = when (val session = state.session) {
                SessionUiState.Loading -> "Restoring DPAPI-protected linked session…"
                SessionUiState.SignedOut -> "Not linked"
                is SessionUiState.SignedIn ->
                    "Linked user: ${session.userId} · Device: ${session.deviceId}"
                is SessionUiState.Error -> "Session unavailable: ${session.code}"
            },
            signedIn = signedIn,
            currentUserId = (state.session as? SessionUiState.SignedIn)?.userId,
            chats = state.chats.values(),
            chatsStatus = state.chats.status("No private chats"),
            messages = state.thread.values(),
            threadStatus = state.thread.status("No messages"),
            activeChatId = state.activeChatId,
            sendEnabled = signedIn && state.activeChatId != null && privateSendingEnabled,
            trustLabel = trustSummary,
            deliveryBanner = deliverySummary,
        )
    }

    private fun <T> UiLoadState<List<T>>.values(): List<T> = when (this) {
        is UiLoadState.Content -> value
        is UiLoadState.Error -> cached.orEmpty()
        is UiLoadState.Empty, UiLoadState.Loading -> emptyList()
    }

    private fun <T> UiLoadState<List<T>>.status(emptyLabel: String): String? = when (this) {
        UiLoadState.Loading -> "Loading…"
        is UiLoadState.Empty -> if (offline) "$emptyLabel (offline)" else emptyLabel
        is UiLoadState.Content -> if (offline) "Showing non-durable process cache (offline)" else null
        is UiLoadState.Error -> "$code${if (offline) " (offline)" else ""}"
    }
}
