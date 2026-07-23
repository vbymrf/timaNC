package com.tima.client.crypto

import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EncryptedDocumentV2
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.EscrowBlob
import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
import com.tima.client.domain.PersonalMessageEnvelope
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalEncodingGoldenTest {
    private val metadata = DocumentMetadata(formatVersion = 2u, revisionNumber = 3uL)
    private val commitment = ByteArray(32) { it.toByte() }
    private val ephemeralKey = ByteArray(32) { (it + 32).toByte() }
    private val escrowBlob = EscrowBlob(
        region = Region.RU,
        epochId = "E",
        shardId = 4u,
        keyId = "K",
        keyCommitment = commitment,
        ephemeralX25519PublicKey = ephemeralKey,
        mlKem768Ciphertext = byteArrayOf(0xcc.toByte()),
        symmetricKeyWrap = byteArrayOf(0xdd.toByte(), 0xee.toByte()),
    )

    @Test
    fun metadataMatchesRestrictedRfc8785Golden() {
        assertEquals(
            """{"content_mode":"private","format_version":2,"revision_number":3}""",
            CanonicalEncoding.metadataJcs(metadata).decodeToString(),
        )
    }

    @Test
    fun escrowBlobMatchesIndependentGoldenBytes() {
        assertEquals(
            "74696d612f657363726f772d626c6f622f76310000000001000000014500000004000000014b" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "00000001cc00000002ddee",
            CanonicalEncoding.escrowBlobBytes(escrowBlob).toHex(),
        )
    }

    @Test
    fun personalEnvelopeMatchesIndependentGoldenBytes() {
        val envelope = PersonalMessageEnvelope(
            header = EnvelopeHeader(
                messageId = 5uL,
                revisionId = "00112233-4455-6677-8899-aabbccddeeff",
                parentRevisionId = null,
                chatId = "11111111-1111-1111-1111-111111111111",
                senderId = "22222222-2222-2222-2222-222222222222",
                senderDeviceId = "33333333-3333-3333-3333-333333333333",
                protocolVersion = 2u,
                messageKeyId = 6u,
            ),
            document = EncryptedDocumentV2(
                encryptedNodes = listOf(byteArrayOf(0xaa.toByte(), 0xbb.toByte())),
                metadata = metadata,
                presenceBitmap = 1u,
            ),
            keyCommitment = commitment,
            escrowBlob = escrowBlob,
            ratchetEnvelope = null,
            wraps = emptyList(),
            signature = ByteArray(0),
        )

        assertEquals(
            "74696d612f706572736f6e616c2d656e76656c6f70652f7369676e61747572652f763200" +
                "00000002000000000000000500112233445566778899aabbccddeeff00" +
                "00000000000000031111111111111111111111111111111122222222222222222222222222222222" +
                "3333333333333333333333333333333300000001010000000100000002aabb0000" +
                "000000417b22636f6e74656e745f6d6f6465223a2270726976617465222c22666f726d61745f" +
                "76657273696f6e223a322c227265766973696f6e5f6e756d626572223a337d00" +
                "00000006000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "0000007174696d612f657363726f772d626c6f622f76310000000001000000014500000004" +
                "000000014b000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "00000001cc00000002ddee0000000000",
            CanonicalEncoding.signingInput(envelope).toHex(),
        )
    }

    @Test
    fun escrowConfigAndTimeMatchIndependentGoldenBytes() {
        val config = SignedEscrowConfig(
            configVersion = 1u,
            region = Region.RU,
            epochId = "E",
            shardId = 4u,
            validFromEpochSeconds = 7uL,
            validFromNanos = 8u,
            validUntilEpochSeconds = 9uL,
            validUntilNanos = 10u,
            current = EscrowKeySet("K", EscrowPublicKeys(ephemeralKey, byteArrayOf(0xcc.toByte()))),
            next = null,
            signingKeyId = "R",
            signature = ByteArray(0),
        )

        assertEquals(
            "74696d612f657363726f772d636f6e6669672f7369676e61747572652f763100" +
                "00000001000000010000000145000000040000000000000007000000080000000000000009" +
                "0000000a000000014b202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "00000001cc000000000152",
            CanonicalEncoding.escrowConfigInput(config).toHex(),
        )
    }

    @Test
    fun documentAadMatchesIndependentGoldenBytes() {
        val header = EnvelopeHeader(
            messageId = 5uL,
            revisionId = "00112233-4455-6677-8899-aabbccddeeff",
            parentRevisionId = null,
            chatId = "11111111-1111-1111-1111-111111111111",
            senderId = "22222222-2222-2222-2222-222222222222",
            senderDeviceId = "33333333-3333-3333-3333-333333333333",
            protocolVersion = 2u,
            messageKeyId = 6u,
        )

        assertEquals(
            "74696d612f646f63756d656e742d6161642f76320000000002000102030405060708090a0b0c0d" +
                "0e0f101112131415161718191a1b1c1d1e1f0000000100000000417b22636f6e74656e745f6d6f" +
                "6465223a2270726976617465222c22666f726d61745f76657273696f6e223a322c22726576697369" +
                "6f6e5f6e756d626572223a337d00000000",
            CanonicalEncoding.documentAad(header, metadata, commitment, 1u, 0u).toHex(),
        )
    }

    @Test
    fun nonCanonicalUuidTextIsRejected() {
        val badHeader = EnvelopeHeader(
            messageId = 5uL,
            revisionId = "00112233445566778899aabbccddeeff",
            parentRevisionId = null,
            chatId = "11111111-1111-1111-1111-111111111111",
            senderId = "22222222-2222-2222-2222-222222222222",
            senderDeviceId = "33333333-3333-3333-3333-333333333333",
            protocolVersion = 2u,
            messageKeyId = 6u,
        )
        val envelope = PersonalMessageEnvelope(
            badHeader,
            EncryptedDocumentV2(listOf(byteArrayOf(1)), metadata, 1u),
            commitment,
            escrowBlob,
            null,
            emptyList(),
            ByteArray(0),
        )

        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoding.signingInput(envelope)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
