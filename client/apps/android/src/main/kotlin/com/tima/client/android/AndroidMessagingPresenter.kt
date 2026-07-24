package com.tima.client.android

import com.tima.client.data.ChatPreview
import com.tima.client.data.MessageBubble
import com.tima.client.data.MessagingUiState
import com.tima.client.data.SessionUiState
import com.tima.client.data.UiLoadState

data class AndroidMessagingViewState(
    val sessionLabel: String,
    val signedIn: Boolean,
    val currentUserId: String?,
    val chats: List<ChatPreview>,
    val chatsStatus: String?,
    val messages: List<MessageBubble>,
    val threadStatus: String?,
    val sendEnabled: Boolean,
    val trustLabel: String,
)

object AndroidMessagingPresenter {
    fun present(
        state: MessagingUiState,
        privateSendingEnabled: Boolean,
        trustSummary: String,
    ): AndroidMessagingViewState {
        val signedIn = state.session is SessionUiState.SignedIn
        val currentUserId = (state.session as? SessionUiState.SignedIn)?.userId
        return AndroidMessagingViewState(
            sessionLabel = when (val session = state.session) {
                SessionUiState.Loading -> "Restoring secure session…"
                SessionUiState.SignedOut -> "Signed out"
                is SessionUiState.SignedIn -> "Signed in: ${session.userId}\nDevice: ${session.deviceId}"
                is SessionUiState.Error -> "Session unavailable: ${session.code}"
            },
            signedIn = signedIn,
            currentUserId = currentUserId,
            chats = state.chats.values(),
            chatsStatus = state.chats.status("No private chats"),
            messages = state.thread.values(),
            threadStatus = state.thread.status("No messages"),
            sendEnabled = signedIn && state.activeChatId != null && privateSendingEnabled,
            trustLabel = trustSummary,
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
        is UiLoadState.Content -> if (offline) "Showing process cache (offline)" else null
        is UiLoadState.Error -> "$code${if (offline) " (offline)" else ""}"
    }
}
