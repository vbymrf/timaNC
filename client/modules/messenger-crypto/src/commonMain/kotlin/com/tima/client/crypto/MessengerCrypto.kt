package com.tima.client.crypto

import com.tima.client.domain.CURRENT_PROTOCOL_VERSION
import com.tima.client.domain.DevicePublicKeys
import com.tima.client.domain.DocumentV2Policy
import com.tima.client.domain.EncryptedDocumentV2
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.PRIVATE_CONTENT_MODE
import com.tima.client.domain.PersonalMessageEnvelope
import com.tima.client.domain.PlainTextDocumentV2
import com.tima.client.domain.VerifiedEscrowConfig
import com.tima.client.domain.WrappedMessageKey
import io.kodium.Kodium
import io.kodium.KodiumPrivateKey
import io.kodium.KodiumPublicKey
import io.kodium.ratchet.HKDF
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class DeviceIdentity private constructor(internal val privateKey: KodiumPrivateKey) {
    val publicKeys: DevicePublicKeys
        get() = privateKey.getPublicKey().let {
            DevicePublicKeys(it.encryptionKey.copyOf(), it.signingKey.copyOf())
        }

    companion object {
        fun generate(): DeviceIdentity = DeviceIdentity(Kodium.generateKeyPair())

        fun fromSeed(seed: ByteArray): DeviceIdentity {
            require(seed.size == 32) { "device seed must be 32 bytes" }
            return DeviceIdentity(KodiumPrivateKey.fromRaw(seed.copyOf()))
        }
    }
}

object KeyCommitments {
    private val INFO = "tima/commit/v1".encodeToByteArray()

    fun derive(messageKey: ByteArray): ByteArray =
        HKDF.deriveSecrets(null, messageKey, INFO, 32)

    fun matches(messageKey: ByteArray, expected: ByteArray): Boolean =
        constantTimeEquals(derive(messageKey), expected)

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) {
            difference = difference or (left[index].toInt() xor right[index].toInt())
        }
        return difference == 0
    }
}

object CanonicalEd25519 {
    // Ed25519 subgroup order L, little-endian. Canonical-S means 0 <= S < L.
    private val L = byteArrayOf(
        0xed.toByte(), 0xd3.toByte(), 0xf5.toByte(), 0x5c, 0x1a, 0x63, 0x12, 0x58,
        0xd6.toByte(), 0x9c.toByte(), 0xf7.toByte(), 0xa2.toByte(), 0xde.toByte(),
        0xf9.toByte(), 0xde.toByte(), 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0x10,
    )

    fun isCanonical(signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        for (index in 31 downTo 0) {
            val s = signature[index + 32].toInt() and 0xff
            val order = L[index].toInt() and 0xff
            if (s < order) return true
            if (s > order) return false
        }
        return false
    }

    fun verify(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        isCanonical(signature) && Kodium.verifyDetached(publicSigningKey, message, signature)
}

class MessengerCrypto(
    private val escrowBlobBuilder: EscrowBlobBuilder,
) {
    fun encryptTextMessage(
        sender: DeviceIdentity,
        header: EnvelopeHeader,
        document: PlainTextDocumentV2,
        recipientDevices: Map<String, DevicePublicKeys>,
        escrowConfig: VerifiedEscrowConfig,
        ratchetEnvelope: ByteArray? = null,
    ): PersonalMessageEnvelope {
        validate(header, document)
        require(recipientDevices.isNotEmpty()) { "at least one Path-B device wrap is required" }
        val messageKey = Kodium.generateHighEntropyKey()
        try {
            val commitment = KeyCommitments.derive(messageKey)
            val presenceBitmap =
                (if (document.textNodes.isNotEmpty()) 0b0001u else 0u) or
                    (if (document.markup != null) 0b0100u else 0u) or
                    (if (document.secretMetadata != null) 0b1000u else 0u)
            val encryptedNodes = document.textNodes.mapIndexed { index, node ->
                val aad = CanonicalEncoding.documentAad(
                    header, document.metadata, commitment, presenceBitmap, index.toUInt(),
                    document.markup,
                )
                val nodeKey = deriveNodeKey(messageKey, aad)
                try {
                    Kodium.encryptSymmetric(nodeKey, node.encodeToByteArray()).getOrThrow()
                } finally {
                    nodeKey.fill(0)
                }
            }
            val encryptedMetadata = document.secretMetadata?.let {
                val aad = CanonicalEncoding.metadataAad(
                    header, document.metadata, commitment, presenceBitmap, document.markup,
                )
                val metadataKey = deriveMetadataKey(messageKey, aad)
                try {
                    Kodium.encryptSymmetric(
                        metadataKey,
                        DocumentV2Policy.canonicalJson(it),
                    ).getOrThrow()
                } finally {
                    metadataKey.fill(0)
                }
            }
            val encryptedDocument = EncryptedDocumentV2(
                encryptedNodes = encryptedNodes,
                markup = document.markup,
                encryptedMetadata = encryptedMetadata,
                metadata = document.metadata,
                presenceBitmap = presenceBitmap,
            )
            val wraps = recipientDevices.entries.map { (deviceId, publicKeys) ->
                wrapForDevice(deviceId, publicKeys, messageKey, commitment)
            }
            val escrowBlob = escrowBlobBuilder.build(escrowConfig, messageKey, commitment)
            val unsigned = PersonalMessageEnvelope(
                header = header,
                document = encryptedDocument,
                keyCommitment = commitment,
                escrowBlob = escrowBlob,
                ratchetEnvelope = ratchetEnvelope?.copyOf(),
                wraps = wraps,
                signature = ByteArray(0),
            )
            val signature = Kodium.signDetached(
                sender.privateKey,
                CanonicalEncoding.signingInput(unsigned),
            ).getOrThrow()
            check(CanonicalEd25519.isCanonical(signature)) { "Kodium produced non-canonical Ed25519 S" }
            return unsigned.copy(signature = signature)
        } finally {
            messageKey.fill(0)
        }
    }

    fun decryptTextMessageViaPathB(
        recipientDeviceId: String,
        recipient: DeviceIdentity,
        senderPublicKeys: DevicePublicKeys,
        envelope: PersonalMessageEnvelope,
    ): PlainTextDocumentV2 {
        require(CanonicalEd25519.verify(
            senderPublicKeys.ed25519,
            CanonicalEncoding.signingInput(envelope),
            envelope.signature,
        )) { "invalid envelope signature" }
        validateEncrypted(envelope)
        val wrap = envelope.wraps.singleOrNull { it.recipientDeviceId == recipientDeviceId }
            ?: error("missing Path-B wrap")
        require(wrap.keyCommitment.contentEquals(envelope.keyCommitment)) {
            "wrap commitment differs from envelope"
        }
        val ephemeral = KodiumPublicKey(
            wrap.ephemeralPublicKeys.x25519,
            wrap.ephemeralPublicKeys.ed25519,
        )
        val messageKey = Kodium.decrypt(recipient.privateKey, ephemeral, wrap.ciphertext).getOrThrow()
        try {
            require(KeyCommitments.matches(messageKey, envelope.keyCommitment)) {
                "commitment_mismatch"
            }
            val metadata = envelope.document.metadata
            val nodes = envelope.document.encryptedNodes.mapIndexed { index, cipher ->
                val aad = CanonicalEncoding.documentAad(
                    envelope.header,
                    metadata,
                    envelope.keyCommitment,
                    envelope.document.presenceBitmap,
                    index.toUInt(),
                    envelope.document.markup,
                )
                val nodeKey = deriveNodeKey(messageKey, aad)
                try {
                    Kodium.decryptSymmetric(nodeKey, cipher).getOrThrow().decodeToString()
                } finally {
                    nodeKey.fill(0)
                }
            }
            val secretMetadata = envelope.document.encryptedMetadata?.let { cipher ->
                val aad = CanonicalEncoding.metadataAad(
                    envelope.header,
                    metadata,
                    envelope.keyCommitment,
                    envelope.document.presenceBitmap,
                    envelope.document.markup,
                )
                val metadataKey = deriveMetadataKey(messageKey, aad)
                try {
                    Json.parseToJsonElement(
                        Kodium.decryptSymmetric(metadataKey, cipher).getOrThrow().decodeToString(),
                    ).jsonObject
                } finally {
                    metadataKey.fill(0)
                }
            }
            return PlainTextDocumentV2(
                textNodes = nodes,
                markup = envelope.document.markup,
                secretMetadata = secretMetadata,
                metadata = metadata,
            )
        } finally {
            messageKey.fill(0)
        }
    }

    private fun wrapForDevice(
        deviceId: String,
        recipient: DevicePublicKeys,
        messageKey: ByteArray,
        commitment: ByteArray,
    ): WrappedMessageKey {
        require(recipient.x25519.size == 32) { "invalid recipient X25519 key" }
        require(recipient.ed25519.size == 32) { "invalid recipient Ed25519 key" }
        val ephemeral = Kodium.generateKeyPair()
        val publicKey = KodiumPublicKey(recipient.x25519, recipient.ed25519)
        return WrappedMessageKey(
            recipientDeviceId = deviceId,
            ephemeralPublicKeys = ephemeral.getPublicKey().let {
                DevicePublicKeys(it.encryptionKey.copyOf(), it.signingKey.copyOf())
            },
            ciphertext = Kodium.encrypt(ephemeral, publicKey, messageKey).getOrThrow(),
            keyCommitment = commitment.copyOf(),
        )
    }

    private fun deriveNodeKey(messageKey: ByteArray, aad: ByteArray): ByteArray =
        HKDF.deriveSecrets(
            salt = null,
            ikm = messageKey,
            info = "tima/document-v2/node-key/v1".encodeToByteArray() + aad,
            length = 32,
        )

    private fun deriveMetadataKey(messageKey: ByteArray, aad: ByteArray): ByteArray =
        HKDF.deriveSecrets(
            salt = null,
            ikm = messageKey,
            info = "tima/document-v2/metadata-key/v1".encodeToByteArray() + aad,
            length = 32,
        )

    private fun validate(header: EnvelopeHeader, document: PlainTextDocumentV2) {
        require(header.protocolVersion == CURRENT_PROTOCOL_VERSION) { "unsupported protocol version" }
        require(document.metadata.formatVersion == CURRENT_PROTOCOL_VERSION) { "unsupported document version" }
        require(header.protocolVersion == document.metadata.formatVersion) { "version lockstep mismatch" }
        require(document.metadata.contentMode == PRIVATE_CONTENT_MODE) { "private content required" }
        require(document.metadata.revisionNumber > 0u) { "revision number must be positive" }
    }

    private fun validateEncrypted(envelope: PersonalMessageEnvelope) {
        val document = envelope.document
        require(envelope.header.protocolVersion == CURRENT_PROTOCOL_VERSION)
        require(document.metadata.formatVersion == CURRENT_PROTOCOL_VERSION)
        require(envelope.header.protocolVersion == document.metadata.formatVersion)
        require(document.metadata.contentMode == PRIVATE_CONTENT_MODE)
        require(document.metadata.revisionNumber > 0u)
        val expectedBitmap =
            (if (document.encryptedNodes.isNotEmpty()) 0b0001u else 0u) or
                (if (document.markup != null) 0b0100u else 0u) or
                (if (document.encryptedMetadata != null) 0b1000u else 0u)
        require(document.presenceBitmap == expectedBitmap) { "non-canonical presence bitmap" }
        require(envelope.keyCommitment.size == 32) { "missing key commitment" }
        require(envelope.escrowBlob.keyCommitment.contentEquals(envelope.keyCommitment)) {
            "escrow commitment differs from envelope"
        }
    }
}
