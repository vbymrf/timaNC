package com.tima.client.network

import com.tima.client.crypto.CanonicalEncoding
import com.tima.client.domain.DevicePublicKeys
import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EncryptedDocumentV2
import com.tima.client.domain.PersonalMessageEnvelope
import com.tima.client.domain.WrappedMessageKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface CryptoTransportAdapter<TransportEnvelope> {
    fun toTransport(value: PersonalMessageEnvelope): TransportEnvelope
}

data class PrivateMessageWriteDto(
    val sender_id: String,
    val message_id: String,
    val revision_id: String,
    val message_key_id: Int,
    val document: PrivateDocumentEnvelopeDto,
    val wrapped_keys: List<WrappedKeyDto>,
)

data class PrivateDocumentEnvelopeDto(
    val encrypted_nodes: List<String>,
    val metadata: PrivateMetadataDto,
    val protocol_version: Int,
    val presence_bitmap: UInt,
    val key_commitment: String,
    val escrow_blob: String,
    val ratchet_envelope: String?,
    val signature: String,
)

data class PrivateMetadataDto(
    val content_mode: String,
    val format_version: UInt,
    val revision_number: ULong,
)

data class WrappedKeyDto(
    val device_id: String,
    val wrapped_key: String,
    val protocol_version: Int,
    val key_commitment: String,
)

data class MessageReservationDto(
    val message_id: String,
    val revision_id: String,
    val expires_at: String,
)

data class ReservedMessageIds(
    val messageId: ULong,
    val revisionId: String,
)

enum class DevicePlatformDto(val wireValue: String) {
    IOS("ios"),
    ANDROID("android"),
    WINDOWS("windows"),
}

data class DeviceRegistrationDto(
    val name: String,
    val platform: DevicePlatformDto,
    val identity_public_key: String,
    val signing_public_key: String,
    val app_version: String?,
)

data class PrivateMessageHistoryDto(
    val id: String,
    val conversation_id: String,
    val sender_id: String,
    val current_revision_id: String,
    val created_at: String,
    val document: PrivateDocumentEnvelopeDto,
    val wrapped_keys: List<WrappedKeyDto>,
    val deleted_at: String?,
)

/**
 * History does not return sender_device_id, message_key_id, parent_revision_id, or the signed
 * chat routing header, so it cannot safely be inflated into a complete PersonalMessageEnvelope.
 */
data class HistoryCryptoProjection(
    val messageId: ULong,
    val conversationId: String,
    val senderId: String,
    val revisionId: String,
    val document: EncryptedDocumentV2,
    val keyCommitment: ByteArray,
    val canonicalEscrowBlob: ByteArray,
    val ratchetEnvelope: ByteArray?,
    val signature: ByteArray,
    val wrappedKeys: List<TransportWrappedKeyProjection>,
)

data class TransportWrappedKeyProjection(
    val deviceId: String,
    val ephemeralX25519PublicKey: ByteArray,
    val ciphertext: ByteArray,
    val keyCommitment: ByteArray,
)

@OptIn(ExperimentalEncodingApi::class)
object RestCryptoTransportAdapter : CryptoTransportAdapter<PrivateMessageWriteDto> {
    private val MAX_DECIMAL_INT64 = Long.MAX_VALUE.toULong()
    private const val PROTOCOL_VERSION = 2
    private const val EPHEMERAL_X25519_SIZE = 32

    override fun toTransport(value: PersonalMessageEnvelope): PrivateMessageWriteDto {
        val header = value.header
        requireUuid(header.revisionId, "revision_id")
        requireUuid(header.senderId, "sender_id")
        require(header.messageKeyId <= Int.MAX_VALUE.toUInt()) {
            "message_key_id exceeds REST integer range"
        }
        require(value.document.presenceBitmap == 1u) { "only strict text-only documents are supported" }
        require(value.document.encryptedNodes.isNotEmpty()) { "encrypted_nodes must not be empty" }
        require(value.document.encryptedNodes.all { it.isNotEmpty() }) {
            "encrypted_nodes must not contain empty ciphertext"
        }
        require(value.wraps.isNotEmpty()) { "wrapped_keys must not be empty" }
        require(value.keyCommitment.size == 32) { "key_commitment must be 32 bytes" }
        require(value.signature.isNotEmpty()) { "signature must not be empty" }

        return PrivateMessageWriteDto(
            sender_id = header.senderId,
            message_id = encodeDecimalInt64(header.messageId),
            revision_id = header.revisionId,
            message_key_id = header.messageKeyId.toInt(),
            document = PrivateDocumentEnvelopeDto(
                encrypted_nodes = value.document.encryptedNodes.map(::encodeBase64),
                metadata = value.document.metadata.toTransport(),
                protocol_version = header.protocolVersion.toRestProtocolVersion(),
                presence_bitmap = value.document.presenceBitmap,
                key_commitment = encodeBase64(value.keyCommitment),
                escrow_blob = encodeBase64(CanonicalEncoding.escrowBlobBytes(value.escrowBlob)),
                ratchet_envelope = value.ratchetEnvelope?.let(::encodeBase64),
                signature = encodeBase64(value.signature),
            ),
            wrapped_keys = value.wraps.map { it.toTransport(header.protocolVersion) },
        )
    }

    fun fromHistory(value: PrivateMessageHistoryDto): HistoryCryptoProjection {
        requireUuid(value.conversation_id, "conversation_id")
        requireUuid(value.sender_id, "sender_id")
        requireUuid(value.current_revision_id, "current_revision_id")
        val document = value.document
        require(document.protocol_version == PROTOCOL_VERSION) { "unsupported protocol_version" }
        require(document.presence_bitmap == 1u) { "only strict text-only documents are supported" }
        require(document.encrypted_nodes.isNotEmpty()) { "encrypted_nodes must not be empty" }
        val encryptedNodes = document.encrypted_nodes.map(::decodeCanonicalBase64)
        require(encryptedNodes.all { it.isNotEmpty() }) { "encrypted_nodes contains empty ciphertext" }
        val keyCommitment = decodeFixed32(document.key_commitment, "key_commitment")
        val canonicalEscrowBlob = decodeCanonicalBase64(document.escrow_blob)
        require(canonicalEscrowBlob.hasPrefix(ESCROW_BLOB_DOMAIN)) {
            "escrow_blob is not a canonical tima/escrow-blob/v1 value"
        }
        val signature = decodeCanonicalBase64(document.signature)
        require(signature.isNotEmpty()) { "signature must not be empty" }
        val wrappedKeys = value.wrapped_keys.map(::decodeWrappedKey)
        require(wrappedKeys.isNotEmpty()) { "wrapped_keys must not be empty" }
        require(wrappedKeys.all { it.keyCommitment.contentEquals(keyCommitment) }) {
            "wrapped key commitment differs from document"
        }
        return HistoryCryptoProjection(
            messageId = decodeDecimalInt64(value.id),
            conversationId = value.conversation_id,
            senderId = value.sender_id,
            revisionId = value.current_revision_id,
            document = EncryptedDocumentV2(
                encryptedNodes = encryptedNodes,
                metadata = document.metadata.toDomain(),
                presenceBitmap = document.presence_bitmap,
            ),
            keyCommitment = keyCommitment,
            canonicalEscrowBlob = canonicalEscrowBlob,
            ratchetEnvelope = document.ratchet_envelope?.let(::decodeCanonicalBase64),
            signature = signature,
            wrappedKeys = wrappedKeys,
        )
    }

    fun reservation(value: MessageReservationDto): ReservedMessageIds {
        requireUuid(value.revision_id, "revision_id")
        require(value.expires_at.isNotBlank()) { "expires_at must not be blank" }
        return ReservedMessageIds(decodeDecimalInt64(value.message_id), value.revision_id)
    }

    fun deviceRegistration(
        name: String,
        platform: DevicePlatformDto,
        publicKeys: DevicePublicKeys,
        appVersion: String? = null,
    ): DeviceRegistrationDto {
        require(name.isNotBlank() && name.length <= 100) { "invalid device name" }
        require(publicKeys.x25519.size == 32) { "identity_public_key must be 32 bytes" }
        require(publicKeys.ed25519.size == 32) { "signing_public_key must be 32 bytes" }
        require(appVersion == null || appVersion.length <= 32) { "app_version is too long" }
        return DeviceRegistrationDto(
            name = name,
            platform = platform,
            identity_public_key = encodeBase64(publicKeys.x25519),
            signing_public_key = encodeBase64(publicKeys.ed25519),
            app_version = appVersion,
        )
    }

    fun encodeDecimalInt64(value: ULong): String {
        require(value in 1uL..MAX_DECIMAL_INT64) { "message_id is outside DecimalInt64String range" }
        return value.toString()
    }

    fun decodeDecimalInt64(value: String): ULong {
        require(value.matches(Regex("^[1-9][0-9]{0,18}$"))) { "invalid DecimalInt64String" }
        val parsed = value.toULongOrNull() ?: error("invalid DecimalInt64String")
        require(parsed <= MAX_DECIMAL_INT64) { "DecimalInt64String exceeds PostgreSQL BIGINT" }
        return parsed
    }

    private fun WrappedMessageKey.toTransport(protocolVersion: UInt): WrappedKeyDto {
        requireUuid(recipientDeviceId, "device_id")
        require(ephemeralPublicKeys.x25519.size == EPHEMERAL_X25519_SIZE) {
            "ephemeral X25519 public key must be 32 bytes"
        }
        require(ciphertext.isNotEmpty()) { "wrapped key ciphertext must not be empty" }
        require(keyCommitment.size == 32) { "wrapped key commitment must be 32 bytes" }
        return WrappedKeyDto(
            device_id = recipientDeviceId,
            // REST wrapped_key is opaque: prefix the Box ciphertext with its required ephemeral key.
            wrapped_key = encodeBase64(ephemeralPublicKeys.x25519 + ciphertext),
            protocol_version = protocolVersion.toRestProtocolVersion(),
            key_commitment = encodeBase64(keyCommitment),
        )
    }

    private fun decodeWrappedKey(value: WrappedKeyDto): TransportWrappedKeyProjection {
        requireUuid(value.device_id, "device_id")
        require(value.protocol_version == PROTOCOL_VERSION) { "unsupported wrapped-key protocol_version" }
        val packed = decodeCanonicalBase64(value.wrapped_key)
        require(packed.size > EPHEMERAL_X25519_SIZE) { "wrapped_key is missing ciphertext" }
        return TransportWrappedKeyProjection(
            deviceId = value.device_id,
            ephemeralX25519PublicKey = packed.copyOfRange(0, EPHEMERAL_X25519_SIZE),
            ciphertext = packed.copyOfRange(EPHEMERAL_X25519_SIZE, packed.size),
            keyCommitment = decodeFixed32(value.key_commitment, "wrapped key commitment"),
        )
    }

    private fun PrivateMetadataDto.toDomain(): DocumentMetadata {
        require(content_mode == "private") { "history metadata is not private" }
        require(format_version == 2u) { "unsupported metadata format_version" }
        require(revision_number > 0uL) { "revision_number must be positive" }
        return DocumentMetadata(format_version, revision_number, content_mode)
    }

    private fun DocumentMetadata.toTransport(): PrivateMetadataDto {
        require(contentMode == "private" && formatVersion == 2u && revisionNumber > 0uL) {
            "unsupported private metadata"
        }
        return PrivateMetadataDto(contentMode, formatVersion, revisionNumber)
    }

    private fun UInt.toRestProtocolVersion(): Int {
        require(this == 2u) { "unsupported protocol_version" }
        return PROTOCOL_VERSION
    }

    private fun encodeBase64(value: ByteArray): String = Base64.Default.encode(value)

    private fun decodeFixed32(value: String, name: String): ByteArray =
        decodeCanonicalBase64(value).also { require(it.size == 32) { "$name must be 32 bytes" } }

    private fun decodeCanonicalBase64(value: String): ByteArray {
        val decoded = try {
            Base64.Default.decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64", error)
        }
        require(Base64.Default.encode(decoded) == value) { "base64 must use canonical padded RFC4648 form" }
        return decoded
    }

    private fun requireUuid(value: String, name: String) {
        require(value.length == 36) { "$name must be a canonical UUID" }
        require(value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-') {
            "$name must be a canonical UUID"
        }
        val compact = value.filterNot { it == '-' }
        require(compact.length == 32 && compact.all { it.isHexDigit() }) {
            "$name must be a canonical UUID"
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val ESCROW_BLOB_DOMAIN = "tima/escrow-blob/v1\u0000".encodeToByteArray()
}
