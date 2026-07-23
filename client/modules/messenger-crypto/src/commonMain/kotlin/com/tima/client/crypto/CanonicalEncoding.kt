package com.tima.client.crypto

import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EncryptedDocumentV2
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.EscrowBlob
import com.tima.client.domain.PersonalMessageEnvelope
import com.tima.client.domain.SignedEscrowConfig

object CanonicalEncoding {
    private const val PERSONAL_SIGNATURE_DOMAIN = "tima/personal-envelope/signature/v2"
    private const val ESCROW_BLOB_DOMAIN = "tima/escrow-blob/v1"
    private const val ESCROW_CONFIG_DOMAIN = "tima/escrow-config/signature/v1"
    private const val DOCUMENT_AAD_DOMAIN = "tima/document-aad/v2"

    fun documentAad(
        header: EnvelopeHeader,
        metadata: DocumentMetadata,
        commitment: ByteArray,
        presenceBitmap: UInt,
        nodeIndex: UInt,
    ): ByteArray = Writer()
        .domain(DOCUMENT_AAD_DOMAIN)
        .u32(header.protocolVersion)
        .fixed32(commitment)
        .u32(presenceBitmap)
        .absent() // text-only markup
        .json(metadataJcs(metadata))
        .u32(nodeIndex)
        .result()

    fun metadataJcs(metadata: DocumentMetadata): ByteArray {
        require(metadata.contentMode == "private") { "unsupported restricted metadata content_mode" }
        return (
            """{"content_mode":"private","format_version":${metadata.formatVersion},"revision_number":${metadata.revisionNumber}}"""
            ).encodeToByteArray()
    }

    fun signingInput(envelope: PersonalMessageEnvelope): ByteArray =
        signingInput(
            header = envelope.header,
            document = envelope.document,
            commitment = envelope.keyCommitment,
            escrowBlob = envelope.escrowBlob,
            ratchetEnvelope = envelope.ratchetEnvelope,
        )

    fun signingInput(
        header: EnvelopeHeader,
        document: EncryptedDocumentV2,
        commitment: ByteArray,
        escrowBlob: EscrowBlob,
        ratchetEnvelope: ByteArray?,
    ): ByteArray = Writer()
        .domain(PERSONAL_SIGNATURE_DOMAIN)
        .u32(header.protocolVersion)
        .u64(header.messageId)
        .uuid(header.revisionId)
        .optionalUuid(header.parentRevisionId)
        .u64(document.metadata.revisionNumber)
        .uuid(header.chatId)
        .uuid(header.senderId)
        .uuid(header.senderDeviceId)
        .document(document)
        .u32(header.messageKeyId)
        .fixed32(commitment)
        .bytes(escrowBlobBytes(escrowBlob))
        .optionalBytes(ratchetEnvelope)
        .u32(0u) // media bindings list is empty in this vertical slice
        .result()

    fun escrowBlobBytes(blob: EscrowBlob): ByteArray = Writer()
        .domain(ESCROW_BLOB_DOMAIN)
        .u32(blob.region.canonicalValue)
        .string(blob.epochId)
        .u32(blob.shardId)
        .string(blob.keyId)
        .fixed32(blob.keyCommitment)
        .fixed32(blob.ephemeralX25519PublicKey)
        .bytes(blob.mlKem768Ciphertext)
        .bytes(blob.symmetricKeyWrap)
        .result()

    fun escrowConfigInput(config: SignedEscrowConfig): ByteArray =
        Writer()
            .domain(ESCROW_CONFIG_DOMAIN)
            .u32(config.configVersion)
            .u32(config.region.canonicalValue)
            .string(config.epochId)
            .u32(config.shardId)
            .time(config.validFromEpochSeconds, config.validFromNanos)
            .time(config.validUntilEpochSeconds, config.validUntilNanos)
            .keySet(config.current)
            .optionalKeySet(config.next)
            .string(config.signingKeyId)
            .result()

    private class Writer {
        private val output = mutableListOf<Byte>()

        fun byte(value: Int): Writer = apply { output += value.toByte() }

        fun domain(value: String): Writer = apply {
            require(value.all { it.code in 0x20..0x7e }) { "domain must be ASCII" }
            raw(value.encodeToByteArray())
            byte(0)
        }

        fun u32(value: UInt): Writer = apply {
            for (shift in 24 downTo 0 step 8) output += (value shr shift).toByte()
        }

        fun u64(value: ULong): Writer = apply {
            for (shift in 56 downTo 0 step 8) output += (value shr shift).toByte()
        }

        fun string(value: String): Writer = bytes(value.encodeToByteArray())

        fun bytes(value: ByteArray): Writer = apply {
            u32(value.size.toUInt())
            raw(value)
        }

        fun fixed32(value: ByteArray): Writer = apply {
            require(value.size == 32) { "FIXED32 must contain exactly 32 bytes" }
            raw(value)
        }

        fun json(jcs: ByteArray): Writer = bytes(jcs)

        fun absent(): Writer = byte(0)

        fun optionalBytes(value: ByteArray?): Writer = apply {
            if (value == null || value.isEmpty()) {
                absent()
            } else {
                byte(1)
                bytes(value)
            }
        }

        fun uuid(value: String): Writer = raw(decodeUuid(value))

        fun optionalUuid(value: String?): Writer = apply {
            if (value == null) absent() else {
                byte(1)
                uuid(value)
            }
        }

        fun keySet(value: com.tima.client.domain.EscrowKeySet): Writer = apply {
            string(value.keyId)
            fixed32(value.publicKeys.x25519Threshold)
            bytes(value.publicKeys.mlKem768)
        }

        fun optionalKeySet(value: com.tima.client.domain.EscrowKeySet?): Writer = apply {
            if (value == null) absent() else {
                byte(1)
                keySet(value)
            }
        }

        fun time(seconds: ULong, nanos: UInt): Writer = apply {
            require(nanos <= 999_999_999u) { "TIME nanos out of range" }
            u64(seconds)
            u32(nanos)
        }

        fun document(value: EncryptedDocumentV2): Writer = apply {
            require(value.presenceBitmap == 1u) { "text-only encrypted document bitmap must equal 1" }
            require(value.encryptedNodes.isNotEmpty()) { "encrypted_nodes must be present and non-empty" }
            require(value.encryptedNodes.all { it.isNotEmpty() }) { "encrypted node must not be empty" }
            u32(value.presenceBitmap)
            byte(1)
            u32(value.encryptedNodes.size.toUInt())
            value.encryptedNodes.forEach(::bytes)
            absent() // plaintext nodes
            absent() // markup
            json(metadataJcs(value.metadata))
            absent() // encrypted metadata
        }

        fun raw(value: ByteArray): Writer = apply { output.addAll(value.asList()) }

        fun result(): ByteArray = output.toByteArray()

        private fun decodeUuid(value: String): ByteArray {
            require(value.length == 36) { "UUID must use canonical RFC4122 text" }
            require(value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-') {
                "UUID must use canonical RFC4122 text"
            }
            val compact = value.filterNot { it == '-' }
            require(compact.length == 32 && compact.all { it.isHexDigit() }) {
                "UUID contains invalid hexadecimal data"
            }
            return ByteArray(16) { index ->
                compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
