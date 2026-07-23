package com.tima.client.domain

import kotlinx.serialization.json.JsonObject

const val CURRENT_PROTOCOL_VERSION: UInt = 2u
const val PRIVATE_CONTENT_MODE: String = "private"

enum class Region(val canonicalValue: UInt) {
    RU(1u),
    EU(2u),
}

data class DocumentMetadata(
    val formatVersion: UInt = CURRENT_PROTOCOL_VERSION,
    val revisionNumber: ULong,
    val contentMode: String = PRIVATE_CONTENT_MODE,
)

data class PlainTextDocumentV2(
    val textNodes: List<String> = emptyList(),
    val markup: JsonObject? = null,
    val secretMetadata: JsonObject? = null,
    val metadata: DocumentMetadata,
) {
    init {
        require(textNodes.all(String::isNotEmpty)) { "text nodes must not be empty" }
        require(textNodes.sumOf { it.length } <= 4096) { "document exceeds the text limit" }
        DocumentV2Policy.validate(textNodes.size, markup, secretMetadata)
    }
}

data class EncryptedDocumentV2(
    val encryptedNodes: List<ByteArray>,
    val markup: JsonObject? = null,
    val encryptedMetadata: ByteArray? = null,
    val metadata: DocumentMetadata,
    val presenceBitmap: UInt,
)

data class EnvelopeHeader(
    val messageId: ULong,
    val revisionId: String,
    val parentRevisionId: String?,
    val chatId: String,
    val senderId: String,
    val senderDeviceId: String,
    val protocolVersion: UInt = CURRENT_PROTOCOL_VERSION,
    val messageKeyId: UInt,
)

data class DevicePublicKeys(
    val x25519: ByteArray,
    val ed25519: ByteArray,
)

data class WrappedMessageKey(
    val recipientDeviceId: String,
    val ephemeralPublicKeys: DevicePublicKeys,
    val ciphertext: ByteArray,
    val keyCommitment: ByteArray,
)

data class EscrowPublicKeys(
    val x25519Threshold: ByteArray,
    val mlKem768: ByteArray,
)

data class EscrowKeySet(
    val keyId: String,
    val publicKeys: EscrowPublicKeys,
)

data class SignedEscrowConfig(
    val configVersion: UInt,
    val region: Region,
    val epochId: String,
    val shardId: UInt,
    val validFromEpochSeconds: ULong,
    val validFromNanos: UInt,
    val validUntilEpochSeconds: ULong,
    val validUntilNanos: UInt,
    val current: EscrowKeySet,
    val next: EscrowKeySet?,
    val signingKeyId: String,
    val signature: ByteArray,
)

data class VerifiedEscrowConfig(
    val region: Region,
    val epochId: String,
    val shardId: UInt,
    val selected: EscrowKeySet,
)

data class EscrowBlob(
    val region: Region,
    val epochId: String,
    val shardId: UInt,
    val keyId: String,
    val keyCommitment: ByteArray,
    val ephemeralX25519PublicKey: ByteArray,
    val mlKem768Ciphertext: ByteArray,
    val symmetricKeyWrap: ByteArray,
)

data class PersonalMessageEnvelope(
    val header: EnvelopeHeader,
    val document: EncryptedDocumentV2,
    val keyCommitment: ByteArray,
    val escrowBlob: EscrowBlob,
    val ratchetEnvelope: ByteArray?,
    val wraps: List<WrappedMessageKey>,
    val signature: ByteArray,
)
