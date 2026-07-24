package com.tima.client.windows

import com.tima.client.data.MessageBubble
import com.tima.client.data.MessageDeliveryState
import com.tima.client.data.MessagingUiState
import com.tima.client.data.SessionUiState
import com.tima.client.data.UiLoadState
import com.tima.client.network.SecureStorage
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsMessagingPresenterTest {
    @Test
    fun developmentEscrowRequiresBuildAndEnvironmentOptIn() {
        assertFalse(WindowsDevelopmentModeGate.enabled(false, false))
        assertFalse(WindowsDevelopmentModeGate.enabled(false, true))
        assertFalse(WindowsDevelopmentModeGate.enabled(true, false))
        assertTrue(WindowsDevelopmentModeGate.enabled(true, true))
    }

    @Test
    fun sendingRequiresLinkedSessionThreadAndVerifiedTrust() {
        val linked = MessagingUiState(
            session = SessionUiState.SignedIn("user", "device"),
            activeChatId = "chat",
        )
        assertFalse(present(linked, false).sendEnabled)
        assertTrue(present(linked, true).sendEnabled)
        assertFalse(present(linked.copy(activeChatId = null), true).sendEnabled)
    }

    @Test
    fun presenterKeepsDecryptedMessagesOnlyInViewState() {
        val bubble = MessageBubble(
            localId = "local",
            chatId = "chat",
            messageId = 1u,
            revisionId = "revision",
            revisionNumber = 1u,
            senderUserId = "peer",
            text = "decrypted text",
            createdAt = null,
            delivery = MessageDeliveryState.SENT,
        )
        val view = present(
            MessagingUiState(thread = UiLoadState.Content(listOf(bubble))),
            false,
        )
        assertTrue(view.messages.single() === bubble)
        assertTrue(view.deliveryBanner.contains("No WNS"))
        assertTrue(view.deliveryBanner.contains("periodic"))
    }

    @Test
    fun developmentHttpIsLoopbackOnly() {
        assertTrue(
            WindowsPhase1Runtime.validBaseUrl("http://127.0.0.1:8080", true)
                .startsWith("http://"),
        )
        assertFailsWith<IllegalArgumentException> {
            WindowsPhase1Runtime.validBaseUrl("http://example.test", true)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsPhase1Runtime.validBaseUrl("http://localhost:8080", false)
        }
        assertTrue(
            WindowsPhase1Runtime.validBaseUrl("https://api.example.test", false)
                .startsWith("https://"),
        )
    }

    @Test
    fun messagingIdentityReusesLinkingSeedWithoutMutatingIt() = runBlocking {
        val seed = ByteArray(32) { it.toByte() }
        val storage = InMemorySecureStorage().apply {
            write(WindowsLinkingClient.DEVICE_SEED, seed)
        }
        val identity = WindowsDeviceIdentityRepository(storage).current()
        assertContentEquals(
            identity.publicKeys.x25519,
            WindowsDeviceIdentityRepository(storage).current().publicKeys.x25519,
        )
        assertContentEquals(seed, storage.read(WindowsLinkingClient.DEVICE_SEED))
        assertFalse(
            storage.keys.any { it.contains("messaging", ignoreCase = true) },
            "adapter must not create a parallel messaging identity",
        )
        assertTrue(Base64.getEncoder().encodeToString(identity.publicKeys.ed25519).isNotBlank())
    }

    @Test
    fun messagingIdentityFailsClosedWhenLinkingSeedIsMissing() = runBlocking {
        val storage = InMemorySecureStorage()

        assertFailsWith<IllegalStateException> {
            WindowsDeviceIdentityRepository(storage).current()
        }
        assertTrue(storage.keys.isEmpty(), "messaging must not create a parallel link identity")
    }

    private fun present(state: MessagingUiState, sending: Boolean) =
        WindowsMessagingPresenter.present(
            state,
            sending,
            if (sending) "verified development trust" else "production trust missing",
            "No WNS; periodic authenticated REST catch-up.",
        )

    private class InMemorySecureStorage : SecureStorage {
        val values = mutableMapOf<String, ByteArray>()
        val keys: Set<String> get() = values.keys

        override suspend fun read(key: String): ByteArray? = values[key]?.copyOf()

        override suspend fun write(key: String, value: ByteArray) {
            values[key] = value.copyOf()
        }

        override suspend fun delete(key: String) {
            values.remove(key)?.fill(0)
        }
    }
}
