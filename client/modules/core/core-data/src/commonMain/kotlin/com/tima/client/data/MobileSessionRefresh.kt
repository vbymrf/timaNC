package com.tima.client.data

import com.tima.client.network.SecureStorage
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface RefreshTokenRepository {
    suspend fun current(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

class SecureStorageRefreshTokenRepository(
    private val storage: SecureStorage,
) : RefreshTokenRepository {
    private val mutex = Mutex()

    override suspend fun current(): String? = mutex.withLock {
        storage.read(REFRESH_TOKEN_KEY)?.let { bytes ->
            try {
                bytes.decodeToString().takeIf(String::isNotBlank)
            } finally {
                bytes.fill(0)
            }
        }
    }

    override suspend fun save(token: String) {
        require(token.length >= 32) { "refresh token is malformed" }
        val bytes = token.encodeToByteArray()
        try {
            mutex.withLock { storage.write(REFRESH_TOKEN_KEY, bytes) }
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun clear() {
        mutex.withLock { storage.delete(REFRESH_TOKEN_KEY) }
    }

    private companion object {
        const val REFRESH_TOKEN_KEY = "phase1.auth.refresh-token.v1"
    }
}

class MobileSessionRefresher(
    private val transport: TimaHttpTransport,
    private val sessions: SessionRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val onSession: (ClientSession) -> Unit,
) {
    suspend fun restore(): ClientSession? {
        val current = sessions.current() ?: return null
        val refreshToken = refreshTokens.current() ?: run {
            sessions.clear()
            return null
        }
        val response = try {
            transport.post(
                "/v1/auth/refresh",
                buildJsonObject {
                    put("refresh_token", refreshToken)
                    put("device_id", current.deviceId)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TimaTransportException) {
            if (error.status !in setOf(401, 403)) throw error
            refreshTokens.clear()
            sessions.clear()
            return null
        }

        val userId = response.getValue("user").jsonObject.string("id")
        val deviceId = response.getValue("device").jsonObject.string("id")
        require(userId == current.userId && deviceId == current.deviceId) {
            "refreshed session identity differs from the persisted session"
        }
        val refreshed = ClientSession(
            accessToken = response.string("access_token"),
            userId = userId,
            deviceId = deviceId,
        )
        refreshTokens.save(response.string("refresh_token"))
        sessions.save(refreshed)
        onSession(refreshed)
        return refreshed
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("$name must be a string")
}
