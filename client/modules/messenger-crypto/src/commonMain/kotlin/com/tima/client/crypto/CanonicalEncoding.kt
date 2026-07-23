package com.tima.client.crypto

import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.DocumentV2Policy
import com.tima.client.domain.EncryptedDocumentV2
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.EscrowBlob
import com.tima.client.domain.PersonalMessageEnvelope
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import kotlinx.serialization.json.JsonObject

object CanonicalEncoding {
    private const val PERSONAL_SIGNATURE_DOMAIN = "tima/personal-envelope/signature/v2"
    private const val ESCROW_BLOB_DOMAIN = "tima/escrow-blob/v1"
    private const val ESCROW_CONFIG_DOMAIN = "tima/escrow-config/signature/v1"
    private const val DOCUMENT_AAD_DOMAIN = "tima/document-aad/v2"
    private const val DOCUMENT_METADATA_AAD_DOMAIN = "tima/document-metadata-aad/v2"

    fun documentAad(
        header: EnvelopeHeader,
        metadata: DocumentMetadata,
        commitment: ByteArray,
        presenceBitmap: UInt,
        nodeIndex: UInt,
        markup: JsonObject? = null,
    ): ByteArray = Writer()
        .domain(DOCUMENT_AAD_DOMAIN)
        .u32(header.protocolVersion)
        .fixed32(commitment)
        .u32(presenceBitmap)
        .optionalJson(markup)
        .json(metadataJcs(metadata))
        .u32(nodeIndex)
        .result()

    fun metadataAad(
        header: EnvelopeHeader,
        metadata: DocumentMetadata,
        commitment: ByteArray,
        presenceBitmap: UInt,
        markup: JsonObject?,
    ): ByteArray = Writer()
        .domain(DOCUMENT_METADATA_AAD_DOMAIN)
        .u32(header.protocolVersion)
        .fixed32(commitment)
        .u32(presenceBitmap)
        .optionalJson(markup)
        .json(metadataJcs(metadata))
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

    fun decodeEscrowBlob(value: ByteArray): EscrowBlob {
        val reader = Reader(value)
        reader.domain(ESCROW_BLOB_DOMAIN)
        val region = when (reader.u32()) {
            Region.RU.canonicalValue -> Region.RU
            Region.EU.canonicalValue -> Region.EU
            else -> error("unsupported escrow region")
        }
        val result = EscrowBlob(
            region = region,
            epochId = reader.string(),
            shardId = reader.u32(),
            keyId = reader.string(),
            keyCommitment = reader.fixed32(),
            ephemeralX25519PublicKey = reader.fixed32(),
            mlKem768Ciphertext = reader.bytes(),
            symmetricKeyWrap = reader.bytes(),
        )
        reader.requireFinished()
        return result
    }

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

        fun optionalJson(value: JsonObject?): Writer = apply {
            if (value == null) {
                absent()
            } else {
                byte(1)
                json(DocumentV2Policy.canonicalJson(value))
            }
        }

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
            require(value.encryptedNodes.all { it.isNotEmpty() }) { "encrypted node must not be empty" }
            val expectedBitmap =
                (if (value.encryptedNodes.isNotEmpty()) 1u else 0u) or
                    (if (value.markup != null) 4u else 0u) or
                    (if (value.encryptedMetadata != null) 8u else 0u)
            require(value.presenceBitmap == expectedBitmap) { "non-canonical presence bitmap" }
            u32(value.presenceBitmap)
            if (value.encryptedNodes.isEmpty()) {
                absent()
            } else {
                byte(1)
                u32(value.encryptedNodes.size.toUInt())
                value.encryptedNodes.forEach(::bytes)
            }
            absent() // plaintext nodes
            optionalJson(value.markup)
            json(metadataJcs(value.metadata))
            optionalBytes(value.encryptedMetadata)
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

    private class Reader(private val input: ByteArray) {
        private var position = 0

        fun domain(value: String) {
            val expected = value.encodeToByteArray() + byteArrayOf(0)
            require(raw(expected.size).contentEquals(expected)) { "unexpected canonical domain" }
        }

        fun u32(): UInt = raw(4).fold(0u) { result, byte ->
            (result shl 8) or byte.toUByte().toUInt()
        }

        fun string(): String = bytes().decodeToString(throwOnInvalidSequence = true)

        fun bytes(): ByteArray {
            val size = u32()
            require(size <= Int.MAX_VALUE.toUInt()) { "canonical byte string is too large" }
            return raw(size.toInt())
        }

        fun fixed32(): ByteArray = raw(32)

        fun requireFinished() {
            require(position == input.size) { "trailing canonical bytes" }
        }

        private fun raw(size: Int): ByteArray {
            require(size >= 0 && size <= input.size - position) { "truncated canonical value" }
            return input.copyOfRange(position, position + size).also { position += size }
        }
    }
}
