package com.tima.client.network

import com.tima.client.domain.DevicePublicKeys
import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EncryptedDocumentV2
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.EscrowBlob
import com.tima.client.domain.PersonalMessageEnvelope
import com.tima.client.domain.Region
import com.tima.client.domain.WrappedMessageKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestCryptoTransportAdapterTest {
    @Test
    fun mapsEnvelopeToStrictPrivateMessageWriteShape() {
        val envelope = fixtureEnvelope()

        val mapped = RestCryptoTransportAdapter.toTransport(envelope)

        assertEquals("42", mapped.message_id)
        assertEquals(envelope.header.revisionId, mapped.revision_id)
        assertEquals(envelope.header.senderId, mapped.sender_id)
        assertEquals(7, mapped.message_key_id)
        assertEquals(listOf("AAEC"), mapped.document.encrypted_nodes)
        assertEquals(2, mapped.document.protocol_version)
        assertEquals(1u, mapped.document.presence_bitmap)
        assertEquals("private", mapped.document.metadata.content_mode)
        assertEquals(2u, mapped.document.metadata.format_version)
        assertEquals(1uL, mapped.document.metadata.revision_number)
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            mapped.document.key_commitment,
        )
        assertEquals("AQID", mapped.document.signature)
        assertNull(mapped.document.ratchet_envelope)
        assertEquals(1, mapped.wrapped_keys.size)
        assertEquals(
            "EREREREREREREREREREREREREREREREREREREREREREiMw==",
            mapped.wrapped_keys.single().wrapped_key,
        )
        assertTrue(mapped.document.escrow_blob.startsWith("dGltYS9lc2Nyb3ctYmxvYi92MQA"))
    }

    @Test
    fun reconstructsCompleteRevisionOneEnvelopeFromHistory() {
        val write = RestCryptoTransportAdapter.toTransport(fixtureEnvelope())
        val history = PrivateMessageHistoryDto(
            id = write.message_id,
            conversation_id = CHAT_ID,
            sender_id = write.sender_id,
            sender_device_id = SENDER_DEVICE_ID,
            current_revision_id = write.revision_id,
            message_key_id = write.message_key_id,
            parent_revision_id = null,
            created_at = "2026-07-23T08:00:00Z",
            document = write.document,
            wrapped_keys = write.wrapped_keys,
            deleted_at = null,
        )

        val decoded = RestCryptoTransportAdapter.fromHistory(history)

        assertEquals(42uL, decoded.header.messageId)
        assertEquals(CHAT_ID, decoded.header.chatId)
        assertEquals(write.revision_id, decoded.header.revisionId)
        assertEquals(SENDER_DEVICE_ID, decoded.header.senderDeviceId)
        assertEquals(7u, decoded.header.messageKeyId)
        assertNull(decoded.header.parentRevisionId)
        assertContentEquals(byteArrayOf(0, 1, 2), decoded.document.encryptedNodes.single())
        assertContentEquals(ByteArray(32) { it.toByte() }, decoded.keyCommitment)
        assertEquals("RU-2026Q3-0", decoded.escrowBlob.keyId)
        assertContentEquals(ByteArray(32) { 0x11 }, decoded.wraps.single().ephemeralPublicKeys.x25519)
        assertContentEquals(byteArrayOf(0x22, 0x33), decoded.wraps.single().ciphertext)
    }

    @Test
    fun enforcesDecimalInt64StringConvention() {
        assertEquals(
            Long.MAX_VALUE.toString(),
            RestCryptoTransportAdapter.encodeDecimalInt64(Long.MAX_VALUE.toULong()),
        )
        assertEquals(42uL, RestCryptoTransportAdapter.decodeDecimalInt64("42"))
        assertFailsWith<IllegalArgumentException> {
            RestCryptoTransportAdapter.decodeDecimalInt64("042")
        }
        assertFailsWith<IllegalArgumentException> {
            RestCryptoTransportAdapter.decodeDecimalInt64("9223372036854775808")
        }
        assertFailsWith<IllegalArgumentException> {
            RestCryptoTransportAdapter.encodeDecimalInt64(0uL)
        }
    }

    @Test
    fun mapsReservationAndSeparateDeviceKeys() {
        val reservation = RestCryptoTransportAdapter.reservation(
            MessageReservationDto(
                message_id = "42",
                revision_id = REVISION_ID,
                expires_at = "2026-07-23T08:05:00Z",
            ),
        )
        val registration = RestCryptoTransportAdapter.deviceRegistration(
            name = "Windows test device",
            platform = DevicePlatformDto.WINDOWS,
            publicKeys = DevicePublicKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            appVersion = "0.1.0",
        )

        assertEquals(42uL, reservation.messageId)
        assertEquals(REVISION_ID, reservation.revisionId)
        assertEquals("windows", registration.platform.wireValue)
        assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", registration.identity_public_key)
        assertEquals("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=", registration.signing_public_key)
    }

    @Test
    fun rejectsNonCanonicalBase64FromHistory() {
        val write = RestCryptoTransportAdapter.toTransport(fixtureEnvelope())
        val badDocument = write.document.copy(signature = "AQI")
        val history = PrivateMessageHistoryDto(
            id = "42",
            conversation_id = CHAT_ID,
            sender_id = SENDER_ID,
            sender_device_id = SENDER_DEVICE_ID,
            current_revision_id = REVISION_ID,
            message_key_id = 7,
            parent_revision_id = null,
            created_at = "2026-07-23T08:00:00Z",
            document = badDocument,
            wrapped_keys = write.wrapped_keys,
            deleted_at = null,
        )

        assertFailsWith<IllegalArgumentException> {
            RestCryptoTransportAdapter.fromHistory(history)
        }
    }

    private fun fixtureEnvelope(): PersonalMessageEnvelope {
        val commitment = ByteArray(32) { it.toByte() }
        return PersonalMessageEnvelope(
            header = EnvelopeHeader(
                messageId = 42uL,
                revisionId = REVISION_ID,
                parentRevisionId = null,
                chatId = CHAT_ID,
                senderId = SENDER_ID,
                senderDeviceId = SENDER_DEVICE_ID,
                protocolVersion = 2u,
                messageKeyId = 7u,
            ),
            document = EncryptedDocumentV2(
                encryptedNodes = listOf(byteArrayOf(0, 1, 2)),
                metadata = DocumentMetadata(revisionNumber = 1uL),
                presenceBitmap = 1u,
            ),
            keyCommitment = commitment,
            escrowBlob = EscrowBlob(
                Region.RU,
                "2026Q3",
                0u,
                "RU-2026Q3-0",
                commitment,
                ByteArray(32) { 3 },
                byteArrayOf(4),
                byteArrayOf(5),
            ),
            ratchetEnvelope = null,
            wraps = listOf(
                WrappedMessageKey(
                    recipientDeviceId = "44444444-4444-4444-8444-444444444444",
                    ephemeralPublicKeys = DevicePublicKeys(ByteArray(32) { 0x11 }, ByteArray(32)),
                    ciphertext = byteArrayOf(0x22, 0x33),
                    keyCommitment = commitment,
                ),
            ),
            signature = byteArrayOf(1, 2, 3),
        )
    }

    private companion object {
        const val REVISION_ID = "00000000-0000-4000-8000-000000000042"
        const val CHAT_ID = "11111111-1111-4111-8111-111111111111"
        const val SENDER_ID = "22222222-2222-4222-8222-222222222222"
        const val SENDER_DEVICE_ID = "33333333-3333-4333-8333-333333333333"
    }
}
