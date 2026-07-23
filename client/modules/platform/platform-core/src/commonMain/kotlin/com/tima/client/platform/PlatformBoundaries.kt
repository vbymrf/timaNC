package com.tima.client.platform

import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.GenericPrivatePush
import com.tima.client.network.GenericPushDecoder
import com.tima.client.network.PushTokenProvider
import kotlinx.serialization.json.JsonObject

class PlatformServiceUnavailable(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface SecureSecretStore {
    suspend fun put(alias: String, secret: ByteArray)
    suspend fun get(alias: String): ByteArray?
    suspend fun delete(alias: String)
}

fun interface PushRegistrationGateway {
    suspend fun register(provider: String, token: String)
}

class PushRegistrationCoordinator(
    private val tokenProvider: PushTokenProvider,
    private val gateway: PushRegistrationGateway,
) {
    suspend fun register() {
        val provider = tokenProvider.provider.trim()
        require(provider in setOf("fcm", "apns")) { "unsupported push provider" }
        val token = tokenProvider.currentToken().trim()
        check(token.isNotEmpty()) { "push service returned an empty token" }
        gateway.register(provider, token)
    }
}

fun interface AttestationGate {
    suspend fun authorize(action: String, requestBodySha256: ByteArray): String
}

class CoordinatorAttestationGate(
    private val coordinator: AttestationCoordinator,
) : AttestationGate {
    override suspend fun authorize(action: String, requestBodySha256: ByteArray): String =
        coordinator.tokenFor(action, requestBodySha256).also {
            check(it.isNotBlank()) { "attestation service returned an empty authorization" }
        }
}

/**
 * Sensitive bootstrap is ordered deliberately: vendor proof and server verification must succeed
 * before a push token is read or sent. There is no development-HMAC fallback here.
 */
class TrustedPlatformBootstrap(
    private val attestation: AttestationGate,
    private val pushRegistration: PushRegistrationCoordinator,
) {
    suspend fun run(action: String, requestBodySha256: ByteArray) {
        require(action.isNotBlank())
        require(requestBodySha256.size == 32)
        check(attestation.authorize(action, requestBodySha256.copyOf()).isNotBlank()) {
            "attestation authorization is empty"
        }
        pushRegistration.register()
    }
}

class GenericPushHostAdapter(
    private val onWakeForChat: suspend (GenericPrivatePush) -> Unit,
    private val decoder: GenericPushDecoder = GenericPushDecoder(),
) {
    suspend fun receive(payload: JsonObject) {
        onWakeForChat(decoder.decode(payload))
    }
}
