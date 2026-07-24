package com.tima.client.ios

import com.tima.client.data.MessagingUiState
import com.tima.client.data.SessionUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosMessagingPresenterTest {
    @Test
    fun developmentModeRequiresDebugAndExplicitOptIn() {
        assertFalse(IosDevelopmentModeGate.enabled(false, false))
        assertFalse(IosDevelopmentModeGate.enabled(false, true))
        assertFalse(IosDevelopmentModeGate.enabled(true, false))
        assertTrue(IosDevelopmentModeGate.enabled(true, true))
    }

    @Test
    fun sendingRequiresSessionThreadAndVerifiedTrust() {
        val signedIn = MessagingUiState(
            session = SessionUiState.SignedIn("user", "device"),
            activeChatId = "chat",
        )
        assertFalse(IosMessagingPresenter.present(signedIn, false, "blocked", false).sendEnabled)
        assertTrue(IosMessagingPresenter.present(signedIn, true, "development", false).sendEnabled)
        assertFalse(
            IosMessagingPresenter.present(
                signedIn.copy(activeChatId = null),
                true,
                "development",
                false,
            ).sendEnabled,
        )
    }

    @Test
    fun missingApnsNeverClaimsBackgroundDelivery() {
        val banner = IosMessagingPresenter.present(
            MessagingUiState(),
            false,
            "blocked",
            false,
        ).deliveryBanner
        assertTrue(banner.contains("only while open or when the app resumes"))
        assertFalse(banner.contains("background"))
    }
}
