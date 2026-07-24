package com.tima.client.windows

import com.tima.client.crypto.DeviceIdentity
import com.tima.client.data.ClientSession
import com.tima.client.data.SessionRepository
import com.tima.client.network.SecureStorage
import com.tima.client.network.TimaHttpTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

data class PendingWindowsLink(
    val sessionId: String,
    val claimToken: String,
    val qrPayload: String,
    val expiresAt: String,
)

data class LinkedWindowsSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val deviceId: String,
)

class WindowsLinkingClient(
    private val transport: TimaHttpTransport,
    private val storage: SecureStorage,
    private val sessions: SessionRepository,
) {
    suspend fun start(desktopName: String): PendingWindowsLink {
        require(desktopName.isNotBlank() && desktopName.length <= 100)
        val identity = loadIdentity()
        val response = transport.post(
            "/v1/link/session",
            buildJsonObject {
                put("desktop_public_key", Base64.getEncoder().encodeToString(identity.publicKeys.x25519))
                put("signing_public_key", Base64.getEncoder().encodeToString(identity.publicKeys.ed25519))
                put("desktop_name", desktopName)
            },
        )
        val pending = PendingWindowsLink(
            sessionId = response.string("session_id"),
            claimToken = response.string("claim_token"),
            qrPayload = response.string("qr_payload"),
            expiresAt = response.string("expires_at"),
        )
        storage.write(PENDING_SESSION, pending.sessionId.encodeToByteArray())
        storage.write(PENDING_CLAIM_TOKEN, pending.claimToken.encodeToByteArray())
        return pending
    }

    suspend fun claim(): LinkedWindowsSession {
        val sessionId = storage.requiredString(PENDING_SESSION)
        val claimToken = storage.requiredString(PENDING_CLAIM_TOKEN)
        val response = transport.post(
            "/v1/link/claim",
            buildJsonObject {
                put("session_id", sessionId)
                put("claim_token", claimToken)
            },
        )
        val linked = LinkedWindowsSession(
            accessToken = response.string("access_token"),
            refreshToken = response.string("refresh_token"),
            userId = response.getValue("user").jsonObject.string("id"),
            deviceId = response.getValue("device").jsonObject.string("id"),
        )
        storage.write(REFRESH_TOKEN, linked.refreshToken.encodeToByteArray())
        sessions.save(ClientSession(linked.accessToken, linked.userId, linked.deviceId))
        storage.write(
            WRAPPED_DEVICE_SECRET,
            Base64.getDecoder().decode(response.string("wrapped_device_secret")),
        )
        storage.delete(PENDING_SESSION)
        storage.delete(PENDING_CLAIM_TOKEN)
        return linked
    }

    suspend fun restoredSession(): LinkedWindowsSession? {
        val session = sessions.current() ?: return null
        val bytes = storage.read(REFRESH_TOKEN) ?: return null
        val refresh = try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
        return LinkedWindowsSession(
            session.accessToken,
            refresh,
            session.userId,
            session.deviceId,
        )
    }

    suspend fun saveRefreshedSession(session: ClientSession, refreshToken: String) {
        sessions.save(session)
        storage.write(REFRESH_TOKEN, refreshToken.encodeToByteArray())
    }

    suspend fun clearLinkedSession() {
        sessions.clear()
        storage.delete(REFRESH_TOKEN)
        storage.delete(WRAPPED_DEVICE_SECRET)
        storage.delete(PENDING_SESSION)
        storage.delete(PENDING_CLAIM_TOKEN)
    }

    private suspend fun loadIdentity(): DeviceIdentity {
        val existing = storage.read(DEVICE_SEED)
        val seed = existing ?: ByteArray(32).also(SecureRandom()::nextBytes).also {
            storage.write(DEVICE_SEED, it)
        }
        check(seed.size == 32) { "stored Windows device seed has an invalid length" }
        return try {
            DeviceIdentity.fromSeed(seed)
        } finally {
            seed.fill(0)
        }
    }

    private suspend fun SecureStorage.requiredString(key: String): String =
        read(key)?.decodeToString()?.takeIf { it.isNotBlank() }
            ?: error("no pending Windows link session")

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content

    companion object {
        const val DEVICE_SEED = "device.identity-seed"
        const val REFRESH_TOKEN = "auth.refresh-token"
        const val WRAPPED_DEVICE_SECRET = "device.wrapped-secret"
        const val PENDING_SESSION = "link.pending-session"
        const val PENDING_CLAIM_TOKEN = "link.pending-claim-token"
    }
}
