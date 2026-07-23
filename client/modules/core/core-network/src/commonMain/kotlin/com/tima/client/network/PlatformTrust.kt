package com.tima.client.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

data class AttestationProof(
    val endpoint: String,
    val body: JsonObject,
)

interface AttestationProvider {
    /**
     * Produces a vendor proof bound to SHA-256 of the exact authentication request body.
     */
    suspend fun attest(action: String, requestBodySha256: ByteArray): AttestationProof
}

interface PushTokenProvider {
    val provider: String
    suspend fun currentToken(): String
}

class AttestationCoordinator(
    private val transport: TimaHttpTransport,
    private val provider: AttestationProvider,
) {
    suspend fun tokenFor(
        action: String,
        requestBodySha256: ByteArray,
    ): String {
        require(requestBodySha256.size == 32)
        val proof = provider.attest(action, requestBodySha256.copyOf())
        require(
            proof.endpoint == "/v1/verify/attestation/ios" ||
                proof.endpoint == "/v1/verify/integrity/android",
        )
        return transport.post(proof.endpoint, proof.body)
            .getValue("attestation_token").jsonPrimitive.content
    }
}

data class GenericPrivatePush(
    val chatId: String,
    val collapseKey: String,
)

class GenericPushDecoder {
    fun decode(payload: JsonObject): GenericPrivatePush {
        require(payload.string("type") == "message")
        require(payload.string("preview") == "Новое сообщение")
        require(payload.boolean("encrypted"))
        val forbidden = setOf("sender", "sender_id", "text", "body", "caption", "message_id")
        require(payload.keys.none(forbidden::contains)) { "private push contains forbidden metadata" }
        val chatId = payload.string("chat_id")
        val collapseKey = payload.string("collapse_key")
        require(collapseKey == "chat:$chatId")
        return GenericPrivatePush(chatId, collapseKey)
    }
}

private fun JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.boolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean
