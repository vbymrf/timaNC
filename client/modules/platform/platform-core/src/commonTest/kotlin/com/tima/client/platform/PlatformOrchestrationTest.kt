package com.tima.client.platform

import com.tima.client.network.PushTokenProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlatformOrchestrationTest {
    @Test
    fun attestationFailurePreventsPushTokenAccess() = runTest {
        var pushRead = false
        val attestation = object : AttestationGate {
            override suspend fun authorize(action: String, requestBodySha256: ByteArray): String {
                throw PlatformServiceUnavailable("vendor unavailable")
            }
        }
        val push = object : PushTokenProvider {
            override val provider = "fcm"
            override suspend fun currentToken(): String {
                pushRead = true
                return "must-not-be-read"
            }
        }
        val coordinator = TrustedPlatformBootstrap(
            attestation,
            PushRegistrationCoordinator(push) { _, _ -> error("must not register") },
        )

        assertFailsWith<PlatformServiceUnavailable> {
            coordinator.run("register", ByteArray(32))
        }
        assertFalse(pushRead)
    }

    @Test
    fun windowsClaimTokenIsProtectedAndConsumedOnce() = runTest {
        val sessionId = "01912345-6789-4abc-8def-0123456789ab"
        val store = MemoryStore()
        val gateway = object : WindowsLinkGateway {
            override suspend fun start(
                desktopName: String,
                keys: WindowsLinkKeys,
            ) = WindowsLinkChallenge(
                sessionId,
                "tima://link/v1?session_id=$sessionId&secret=${"a".repeat(43)}",
                "claim-secret",
            )

            override suspend fun claim(sessionId: String, claimToken: String): WindowsLinkedSession {
                assertEquals("claim-secret", claimToken)
                return WindowsLinkedSession(sessionId, "access", byteArrayOf(1))
            }
        }
        val coordinator = WindowsLinkCoordinator(gateway, store)
        val publicChallenge = coordinator.start(
            "Windows PC",
            WindowsLinkKeys(ByteArray(32), ByteArray(32)),
        )
        assertEquals("", publicChallenge.claimToken)

        coordinator.claim(sessionId)
        assertFailsWith<PlatformServiceUnavailable> { coordinator.claim(sessionId) }
    }

    @Test
    fun qrParserRejectsInjectedParameters() {
        val sessionId = "01912345-6789-4abc-8def-0123456789ab"
        assertFailsWith<IllegalArgumentException> {
            WindowsQrLink.parse(
                "tima://link/v1?session_id=$sessionId&secret=${"a".repeat(43)}&redirect=https://evil.test",
            )
        }
    }

    @Test
    fun genericPushRejectsPlaintextBeforeHostCallback() = runTest {
        var delivered = false
        val adapter = GenericPushHostAdapter(onWakeForChat = { delivered = true })
        assertFailsWith<IllegalArgumentException> {
            adapter.receive(buildJsonObject {
                put("type", "message")
                put("chat_id", "chat")
                put("preview", "Новое сообщение")
                put("encrypted", true)
                put("collapse_key", "chat:chat")
                put("body", "private")
            })
        }
        assertFalse(delivered)
    }

    private class MemoryStore : SecureSecretStore {
        private val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(alias: String, secret: ByteArray) {
            values[alias] = secret.copyOf()
        }
        override suspend fun get(alias: String) = values[alias]?.copyOf()
        override suspend fun delete(alias: String) {
            values.remove(alias)?.fill(0)
        }
    }
}
