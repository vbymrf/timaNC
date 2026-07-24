package com.tima.client.android

import com.tima.client.data.MessagingUiState
import com.tima.client.data.SessionUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMessagingPresenterTest {
    @Test
    fun developmentAuthRequiresDebugAndExplicitOptIn() {
        assertFalse(DevelopmentModeGate.enabled(debugBuild = false, explicitDevelopmentAuth = false))
        assertFalse(DevelopmentModeGate.enabled(debugBuild = false, explicitDevelopmentAuth = true))
        assertFalse(DevelopmentModeGate.enabled(debugBuild = true, explicitDevelopmentAuth = false))
        assertTrue(DevelopmentModeGate.enabled(debugBuild = true, explicitDevelopmentAuth = true))
    }

    @Test
    fun sendingRequiresSessionThreadAndTrustConfiguration() {
        val signedIn = MessagingUiState(
            session = SessionUiState.SignedIn("user", "device"),
            activeChatId = "chat",
        )
        assertFalse(
            AndroidMessagingPresenter.present(signedIn, false, "missing production trust").sendEnabled,
        )
        assertTrue(
            AndroidMessagingPresenter.present(signedIn, true, "development trust").sendEnabled,
        )
        assertFalse(
            AndroidMessagingPresenter.present(
                signedIn.copy(activeChatId = null),
                true,
                "development trust",
            ).sendEnabled,
        )
    }
}
