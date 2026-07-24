package com.tima.client.data

import com.tima.client.network.SecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Stores the authenticated session only through the platform SecureStorage implementation.
 * It deliberately has no preferences/file fallback.
 */
class SecureStorageSessionRepository(
    private val storage: SecureStorage,
    private val json: Json = Json,
) : SessionRepository {
    private val mutex = Mutex()

    override suspend fun current(): ClientSession? = mutex.withLock {
        storage.read(SESSION_KEY)?.let { bytes ->
            try {
                val value = json.parseToJsonElement(bytes.decodeToString()).jsonObject
                ClientSession(
                    accessToken = value.getValue("access_token").jsonPrimitive.content,
                    userId = value.getValue("user_id").jsonPrimitive.content,
                    deviceId = value.getValue("device_id").jsonPrimitive.content,
                )
            } finally {
                bytes.fill(0)
            }
        }
    }

    override suspend fun save(session: ClientSession) {
        require(session.accessToken.isNotBlank())
        require(session.userId.isNotBlank())
        require(session.deviceId.isNotBlank())
        val bytes = buildJsonObject {
            put("access_token", session.accessToken)
            put("user_id", session.userId)
            put("device_id", session.deviceId)
        }.toString().encodeToByteArray()
        try {
            mutex.withLock { storage.write(SESSION_KEY, bytes) }
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun clear() {
        mutex.withLock { storage.delete(SESSION_KEY) }
    }

    private companion object {
        const val SESSION_KEY = "phase1.auth.session.v1"
    }
}
