package com.tima.client.integration

import com.tima.client.crypto.DeviceIdentity
import com.tima.client.crypto.EscrowBlobBuilder
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.DocumentV2Policy
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.EscrowBlob
import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
import com.tima.client.domain.PlainTextDocumentV2
import com.tima.client.domain.Region
import com.tima.client.domain.VerifiedEscrowConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

class TwoDeviceRoundTripTest {
    @Test
    fun aliceEncryptsAndBobDecryptsThroughPathB() {
        val alice = DeviceIdentity.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val bob = DeviceIdentity.fromSeed(ByteArray(32) { (it + 65).toByte() })
        val crypto = MessengerCrypto(DeterministicTestEscrowBlobBuilder)
        val header = EnvelopeHeader(
            messageId = 42uL,
            revisionId = "00000000-0000-0000-0000-000000000042",
            parentRevisionId = null,
            chatId = "00000000-0000-0000-0000-000000000001",
            senderId = "00000000-0000-0000-0000-000000000002",
            senderDeviceId = "00000000-0000-0000-0000-000000000003",
            messageKeyId = 1u,
        )
        val plaintext = PlainTextDocumentV2(
            textNodes = listOf("Hello from Alice", "Second text node"),
            metadata = DocumentMetadata(revisionNumber = 1uL),
        )
        val escrow = VerifiedEscrowConfig(
            region = Region.RU,
            epochId = "2026Q3",
            shardId = 3u,
            selected = EscrowKeySet(
                "test-key",
                EscrowPublicKeys(ByteArray(32) { 1 }, ByteArray(1184) { 2 }),
            ),
        )

        val envelope = crypto.encryptTextMessage(
            sender = alice,
            header = header,
            document = plaintext,
            recipientDevices = mapOf(
                "alice-phone" to alice.publicKeys,
                "bob-laptop" to bob.publicKeys,
            ),
            escrowConfig = escrow,
            ratchetEnvelope = null,
        )
        val decrypted = crypto.decryptTextMessageViaPathB(
            recipientDeviceId = "bob-laptop",
            recipient = bob,
            senderPublicKeys = alice.publicKeys,
            envelope = envelope,
        )

        assertEquals(plaintext, decrypted)
        assertEquals(setOf("alice-phone", "bob-laptop"), envelope.wraps.map { it.recipientDeviceId }.toSet())
        assertNull(envelope.ratchetEnvelope)
    }

    @Test
    fun richTextAndMediaSecretsRoundTripThroughPathB() {
        val alice = DeviceIdentity.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val bob = DeviceIdentity.fromSeed(ByteArray(32) { (it + 65).toByte() })
        val crypto = MessengerCrypto(DeterministicTestEscrowBlobBuilder)
        val markup = buildJsonObject {
            put("entities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text_link")
                    put("nodes", buildJsonArray { add(JsonPrimitive(0)) })
                    put("secret_ref", "link.target")
                })
                add(buildJsonObject {
                    put("type", "media")
                    put("media_id", "00000000-0000-0000-0000-000000000099")
                    put("secret_ref", "media.key")
                })
            })
        }
        val plaintext = PlainTextDocumentV2(
            textNodes = listOf("linked media"),
            markup = markup,
            secretMetadata = buildJsonObject {
                put("link.target", "https://example.test/private")
                put("media.key", "base64-media-key")
            },
            metadata = DocumentMetadata(revisionNumber = 1uL),
        )
        assertEquals(
            """{"entities":[{"nodes":[0],"secret_ref":"link.target","type":"text_link"},""" +
                """{"media_id":"00000000-0000-0000-0000-000000000099","secret_ref":"media.key","type":"media"}]}""",
            DocumentV2Policy.canonicalJson(markup).decodeToString(),
        )
        val envelope = crypto.encryptTextMessage(
            sender = alice,
            header = EnvelopeHeader(
                messageId = 43uL,
                revisionId = "00000000-0000-0000-0000-000000000043",
                parentRevisionId = null,
                chatId = "00000000-0000-0000-0000-000000000001",
                senderId = "00000000-0000-0000-0000-000000000002",
                senderDeviceId = "00000000-0000-0000-0000-000000000003",
                messageKeyId = 1u,
            ),
            document = plaintext,
            recipientDevices = mapOf("bob-laptop" to bob.publicKeys),
            escrowConfig = VerifiedEscrowConfig(
                Region.RU,
                "2026Q3",
                3u,
                EscrowKeySet("test-key", EscrowPublicKeys(ByteArray(32) { 1 }, ByteArray(1184) { 2 })),
            ),
        )
        val decrypted = crypto.decryptTextMessageViaPathB(
            "bob-laptop",
            bob,
            alice.publicKeys,
            envelope,
        )

        assertEquals(13u, envelope.document.presenceBitmap)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun mediaOnlyDocumentUsesCanonicalBitmapTwelve() {
        val alice = DeviceIdentity.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val bob = DeviceIdentity.fromSeed(ByteArray(32) { (it + 65).toByte() })
        val crypto = MessengerCrypto(DeterministicTestEscrowBlobBuilder)
        val plaintext = PlainTextDocumentV2(
            markup = buildJsonObject {
                put("entities", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "media")
                        put("media_id", "00000000-0000-0000-0000-000000000099")
                        put("secret_ref", "media.key")
                    })
                })
            },
            secretMetadata = buildJsonObject { put("media.key", "base64-media-key") },
            metadata = DocumentMetadata(revisionNumber = 1uL),
        )
        val envelope = crypto.encryptTextMessage(
            alice,
            EnvelopeHeader(
                44uL,
                "00000000-0000-0000-0000-000000000044",
                null,
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003",
                messageKeyId = 1u,
            ),
            plaintext,
            mapOf("bob-laptop" to bob.publicKeys),
            VerifiedEscrowConfig(
                Region.RU,
                "2026Q3",
                3u,
                EscrowKeySet("test-key", EscrowPublicKeys(ByteArray(32) { 1 }, ByteArray(1184) { 2 })),
            ),
        )

        assertEquals(12u, envelope.document.presenceBitmap)
        assertEquals(
            plaintext,
            crypto.decryptTextMessageViaPathB("bob-laptop", bob, alice.publicKeys, envelope),
        )
    }
}

/**
 * Deliberately non-production escrow fake. It is scoped to test sources and never decrypts or
 * substitutes for the fail-closed/ML-KEM production implementations.
 */
private object DeterministicTestEscrowBlobBuilder : EscrowBlobBuilder {
    override fun build(
        config: VerifiedEscrowConfig,
        messageKey: ByteArray,
        keyCommitment: ByteArray,
    ): EscrowBlob = EscrowBlob(
        region = config.region,
        epochId = config.epochId,
        shardId = config.shardId,
        keyId = config.selected.keyId,
        keyCommitment = keyCommitment.copyOf(),
        ephemeralX25519PublicKey = ByteArray(32) { 0x11 },
        mlKem768Ciphertext = ByteArray(1088) { 0x22 },
        symmetricKeyWrap = messageKey.mapIndexed { index, byte ->
            (byte.toInt() xor keyCommitment[index].toInt()).toByte()
        }.toByteArray(),
    )
}
