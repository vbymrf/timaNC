package com.tima.client.ios

import com.tima.client.data.ChatPreview
import com.tima.client.data.MessageBubble
import com.tima.client.data.MessagingUiState
import com.tima.client.data.SessionUiState
import com.tima.client.data.UiLoadState

data class IosMessagingViewState(
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

object IosDevelopmentModeGate {
    fun enabled(debugBuild: Boolean, explicitDevelopmentAuth: Boolean): Boolean =
        debugBuild && explicitDevelopmentAuth
}

object IosMessagingPresenter {
    fun present(
        state: MessagingUiState,
        privateSendingEnabled: Boolean,
        trustSummary: String,
        apnsAvailable: Boolean,
    ): IosMessagingViewState {
        val signedIn = state.session is SessionUiState.SignedIn
        return IosMessagingViewState(
            sessionLabel = when (val session = state.session) {
                SessionUiState.Loading -> "Restoring secure session…"
                SessionUiState.SignedOut -> "Signed out"
                is SessionUiState.SignedIn ->
                    "Signed in: ${session.userId}\nDevice: ${session.deviceId}"
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
            deliveryBanner = if (apnsAvailable) {
                "APNs token is available; delivery still uses generic wake plus encrypted catch-up."
            } else {
                "APNs is unavailable: messages catch up only while open or when the app resumes."
            },
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
