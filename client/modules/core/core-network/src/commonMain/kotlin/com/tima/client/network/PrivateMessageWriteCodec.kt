package com.tima.client.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Strict codec for ciphertext-only private-message writes stored in the durable client outbox.
 *
 * Decoding accepts only bytes that are already in this codec's canonical form. This makes the
 * persisted bytes the single request body reused by every send attempt.
 */
object PrivateMessageWriteCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun encode(value: PrivateMessageWriteDto): ByteArray {
        validate(value)
        return value.toJson().toString().encodeToByteArray()
    }

    fun decode(bytes: ByteArray): PrivateMessageWriteDto {
        require(bytes.isNotEmpty()) { "private-message envelope is empty" }
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        root.requireKeys(REQUIRED_ROOT_KEYS)
        val document = root.requiredObject("document")
        document.requireKeys(REQUIRED_DOCUMENT_KEYS, OPTIONAL_DOCUMENT_KEYS)
        val metadata = document.requiredObject("metadata")
        metadata.requireKeys(REQUIRED_METADATA_KEYS)
        val wrapped = root.requiredArray("wrapped_keys").map { element ->
            val item = element.jsonObject
            item.requireKeys(REQUIRED_WRAPPED_KEY_KEYS)
            WrappedKeyDto(
                device_id = item.requiredString("device_id"),
                wrapped_key = item.requiredString("wrapped_key"),
                protocol_version = item.requiredInt("protocol_version"),
                key_commitment = item.requiredString("key_commitment"),
            )
        }
        val value = PrivateMessageWriteDto(
            sender_id = root.requiredString("sender_id"),
            message_id = root.requiredString("message_id"),
            revision_id = root.requiredString("revision_id"),
            message_key_id = root.requiredInt("message_key_id"),
            document = PrivateDocumentEnvelopeDto(
                encrypted_nodes = document.optionalArray("encrypted_nodes")
                    ?.map { it.jsonPrimitive.content }.orEmpty(),
                markup = document.optionalObject("markup"),
                encrypted_metadata = document.optionalString("encrypted_metadata"),
                metadata = PrivateMetadataDto(
                    content_mode = metadata.requiredString("content_mode"),
                    format_version = metadata.requiredLong("format_version").toUIntChecked("format_version"),
                    revision_number = metadata.requiredStringNumber("revision_number")
                        .toULongOrNull() ?: error("revision_number is invalid"),
                ),
                protocol_version = document.requiredInt("protocol_version"),
                presence_bitmap = document.requiredLong("presence_bitmap").toUIntChecked("presence_bitmap"),
                key_commitment = document.requiredString("key_commitment"),
                escrow_blob = document.requiredString("escrow_blob"),
                ratchet_envelope = document.optionalString("ratchet_envelope"),
                signature = document.requiredString("signature"),
            ),
            wrapped_keys = wrapped,
        )
        validate(value)
        require(encode(value).contentEquals(bytes)) { "private-message envelope is not canonical" }
        return value
    }

    private fun validate(value: PrivateMessageWriteDto) {
        requireCanonicalUuid(value.sender_id, "sender_id")
        requireCanonicalUuid(value.revision_id, "revision_id")
        RestCryptoTransportAdapter.decodeDecimalInt64(value.message_id)
        require(value.message_key_id >= 0) { "message_key_id must not be negative" }
        require(value.document.metadata.content_mode == "private") { "content_mode must be private" }
        require(value.document.metadata.format_version == 2u) { "unsupported format_version" }
        require(value.document.metadata.revision_number in 1uL..Long.MAX_VALUE.toULong()) {
            "revision_number is outside int64 range"
        }
        require(value.document.protocol_version == 2) { "unsupported protocol_version" }
        require(value.document.key_commitment.isNotBlank()) { "key_commitment must not be blank" }
        require(value.document.escrow_blob.isNotBlank()) { "escrow_blob must not be blank" }
        require(value.document.signature.isNotBlank()) { "signature must not be blank" }
        require(value.wrapped_keys.isNotEmpty()) { "wrapped_keys must not be empty" }
        value.wrapped_keys.forEach {
            requireCanonicalUuid(it.device_id, "wrapped_keys.device_id")
            require(it.protocol_version == 2) { "unsupported wrapped-key protocol_version" }
            require(it.wrapped_key.isNotBlank()) { "wrapped_key must not be blank" }
            require(it.key_commitment == value.document.key_commitment) {
                "wrapped-key commitment differs from document"
            }
        }
    }

    private fun PrivateMessageWriteDto.toJson(): JsonObject = buildJsonObject {
        put("sender_id", sender_id)
        put("message_id", message_id)
        put("revision_id", revision_id)
        put("message_key_id", message_key_id)
        putJsonObject("document") {
            if (document.encrypted_nodes.isNotEmpty()) {
                putJsonArray("encrypted_nodes") {
                    document.encrypted_nodes.forEach { add(JsonPrimitive(it)) }
                }
            }
            document.markup?.let { put("markup", it) }
            document.encrypted_metadata?.let { put("encrypted_metadata", it) }
            putJsonObject("metadata") {
                put("content_mode", document.metadata.content_mode)
                put("format_version", document.metadata.format_version.toInt())
                put("revision_number", document.metadata.revision_number.toLong())
            }
            put("protocol_version", document.protocol_version)
            put("presence_bitmap", document.presence_bitmap.toLong())
            put("key_commitment", document.key_commitment)
            put("escrow_blob", document.escrow_blob)
            document.ratchet_envelope?.let { put("ratchet_envelope", it) }
            put("signature", document.signature)
        }
        putJsonArray("wrapped_keys") {
            wrapped_keys.forEach {
                add(buildJsonObject {
                    put("device_id", it.device_id)
                    put("wrapped_key", it.wrapped_key)
                    put("protocol_version", it.protocol_version)
                    put("key_commitment", it.key_commitment)
                })
            }
        }
    }

    private fun JsonObject.requireKeys(required: Set<String>, optional: Set<String> = emptySet()) {
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) {
            "private-message envelope contains missing or unknown fields"
        }
    }

    private fun JsonObject.required(name: String): JsonElement =
        getValue(name).also { require(it !is JsonNull) { "$name must not be null" } }

    private fun JsonObject.requiredString(name: String): String =
        required(name).jsonPrimitive.also { require(it.isString) { "$name must be a string" } }.content

    private fun JsonObject.requiredStringNumber(name: String): String =
        required(name).jsonPrimitive.also { require(!it.isString) { "$name must be a number" } }.content

    private fun JsonObject.requiredInt(name: String): Int =
        required(name).jsonPrimitive.also { require(!it.isString) { "$name must be a number" } }.int

    private fun JsonObject.requiredLong(name: String): Long =
        required(name).jsonPrimitive.also { require(!it.isString) { "$name must be a number" } }.long

    private fun JsonObject.requiredObject(name: String): JsonObject = required(name).jsonObject
    private fun JsonObject.requiredArray(name: String): JsonArray = required(name).jsonArray
    private fun JsonObject.optionalObject(name: String): JsonObject? =
        get(name)?.takeUnless { it is JsonNull }?.jsonObject

    private fun JsonObject.optionalArray(name: String): JsonArray? =
        get(name)?.takeUnless { it is JsonNull }?.jsonArray

    private fun JsonObject.optionalString(name: String): String? {
        val primitive = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive ?: return null
        require(primitive.isString) { "$name must be a string" }
        return primitive.contentOrNull
    }

    private fun Long.toUIntChecked(name: String): UInt {
        require(this in 0..UInt.MAX_VALUE.toLong()) { "$name is outside uint32 range" }
        return toUInt()
    }

    private fun requireCanonicalUuid(value: String, name: String) {
        require(value.length == 36 && value == value.lowercase()) { "$name must be a canonical UUID" }
        require(value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-') {
            "$name must be a canonical UUID"
        }
        require(value.filterNot { it == '-' }.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$name must be a canonical UUID"
        }
    }

    private val REQUIRED_ROOT_KEYS =
        setOf("sender_id", "message_id", "revision_id", "message_key_id", "document", "wrapped_keys")
    private val REQUIRED_DOCUMENT_KEYS = setOf(
        "metadata", "protocol_version", "presence_bitmap", "key_commitment", "escrow_blob", "signature",
    )
    private val OPTIONAL_DOCUMENT_KEYS =
        setOf("encrypted_nodes", "markup", "encrypted_metadata", "ratchet_envelope")
    private val REQUIRED_METADATA_KEYS = setOf("content_mode", "format_version", "revision_number")
    private val REQUIRED_WRAPPED_KEY_KEYS =
        setOf("device_id", "wrapped_key", "protocol_version", "key_commitment")
}
