package com.tima.client.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Storage for credentials and device key material. Implementations must use the
 * operating system's protected, app-scoped credential facility.
 */
interface SecureStorage {
    suspend fun read(key: String): ByteArray?
    suspend fun write(key: String, value: ByteArray)
    suspend fun delete(key: String)
}

class PlatformServiceUnavailableException(
    service: String,
    cause: Throwable? = null,
) : IllegalStateException("$service is unavailable; the operation was blocked", cause)

/**
 * Small Phase 1 facade shared by the platform shells. It intentionally exposes
 * only trust and generic push registration, not any Phase 2 product surface.
 */
class Phase1PlatformClient(
    private val transport: TimaHttpTransport,
    private val attestation: AttestationCoordinator,
    private val pushTokens: PushTokenProvider,
) {
    suspend fun attestationToken(action: String, requestBodySha256: ByteArray): String =
        attestation.tokenFor(action, requestBodySha256)

    suspend fun registerCurrentPushToken() {
        val token = pushTokens.currentToken()
        check(token.isNotBlank()) { "platform push provider returned an empty token" }
        transport.put(
            path = "/v1/devices/push",
            body = buildJsonObject {
                put("provider", pushTokens.provider)
                put("token", token)
            },
        )
    }
}
