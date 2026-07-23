package com.tima.client.windows

import com.tima.client.crypto.DeviceIdentity
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
    val deviceId: String,
)

class WindowsLinkingClient(
    private val transport: TimaHttpTransport,
    private val storage: SecureStorage,
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
            deviceId = response.getValue("device").jsonObject.string("id"),
        )
        storage.write(ACCESS_TOKEN, linked.accessToken.encodeToByteArray())
        storage.write(REFRESH_TOKEN, linked.refreshToken.encodeToByteArray())
        storage.write(DEVICE_ID, linked.deviceId.encodeToByteArray())
        storage.write(
            WRAPPED_DEVICE_SECRET,
            Base64.getDecoder().decode(response.string("wrapped_device_secret")),
        )
        storage.delete(PENDING_SESSION)
        storage.delete(PENDING_CLAIM_TOKEN)
        return linked
    }

    suspend fun restoredSession(): LinkedWindowsSession? {
        val access = storage.read(ACCESS_TOKEN)?.decodeToString() ?: return null
        val refresh = storage.read(REFRESH_TOKEN)?.decodeToString() ?: return null
        val device = storage.read(DEVICE_ID)?.decodeToString() ?: return null
        return LinkedWindowsSession(access, refresh, device)
    }

    private suspend fun loadIdentity(): DeviceIdentity {
        val existing = storage.read(DEVICE_SEED)
        val seed = existing ?: ByteArray(32).also(SecureRandom()::nextBytes).also {
            storage.write(DEVICE_SEED, it)
        }
        check(seed.size == 32) { "stored Windows device seed has an invalid length" }
        return DeviceIdentity.fromSeed(seed)
    }

    private suspend fun SecureStorage.requiredString(key: String): String =
        read(key)?.decodeToString()?.takeIf { it.isNotBlank() }
            ?: error("no pending Windows link session")

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content

    private companion object {
        const val DEVICE_SEED = "device.identity-seed"
        const val ACCESS_TOKEN = "auth.access-token"
        const val REFRESH_TOKEN = "auth.refresh-token"
        const val DEVICE_ID = "auth.device-id"
        const val WRAPPED_DEVICE_SECRET = "device.wrapped-secret"
        const val PENDING_SESSION = "link.pending-session"
        const val PENDING_CLAIM_TOKEN = "link.pending-claim-token"
    }
}
