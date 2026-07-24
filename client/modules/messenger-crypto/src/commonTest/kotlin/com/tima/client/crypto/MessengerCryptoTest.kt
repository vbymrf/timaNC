package com.tima.client.crypto

import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.PlainTextDocumentV2
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import com.tima.client.domain.VerifiedEscrowConfig
import io.kodium.Kodium
import io.kodium.core.MLKEM
import io.kodium.core.nacl
import io.kodium.ratchet.HKDF
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MessengerCryptoTest {
    @Test
    fun commitmentMatchesDeterministicVector() {
        val messageKey = ByteArray(32) { it.toByte() }

        assertEquals(
            "2cf82a0464d9889d4ba37dad796d3de9eac2d865b5d9f3456aeaaf4a5ad34d65",
            KeyCommitments.derive(messageKey).toHex(),
        )
    }

    @Test
    fun canonicalSignatureRejectsNonCanonicalS() {
        val identity = DeviceIdentity.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val message = "deterministic signed input".encodeToByteArray()
        val signature = Kodium.signDetached(identity.privateKey, message).getOrThrow()

        assertTrue(CanonicalEd25519.verify(identity.publicKeys.ed25519, message, signature))

        val nonCanonical = signature.copyOf()
        nonCanonical.fill(0xff.toByte(), fromIndex = 32)
        assertFalse(CanonicalEd25519.isCanonical(nonCanonical))
        assertFalse(CanonicalEd25519.verify(identity.publicKeys.ed25519, message, nonCanonical))
    }

    @Test
    fun signedEscrowConfigIsTypedAndFailClosed() {
        val root = DeviceIdentity.fromSeed(ByteArray(32) { (0xa0 + it).toByte() })
        val keySet = EscrowKeySet(
            keyId = "ru-2026q3-7-current",
            publicKeys = EscrowPublicKeys(ByteArray(32) { 7 }, ByteArray(MLKEM.PublicKeySize) { 9 }),
        )
        val unsigned = SignedEscrowConfig(
            configVersion = 1u,
            region = Region.RU,
            epochId = "2026Q3",
            shardId = 7u,
            validFromEpochSeconds = 1_700_000_000uL,
            validFromNanos = 123u,
            validUntilEpochSeconds = 1_800_000_000uL,
            validUntilNanos = 456u,
            current = keySet,
            next = null,
            signingKeyId = "root-1",
            signature = ByteArray(0),
        )
        val signature = Kodium.signDetached(
            root.privateKey,
            CanonicalEncoding.escrowConfigInput(unsigned),
        ).getOrThrow()
        val signed = unsigned.copy(signature = signature)
        val verifier = EscrowConfigVerifier(mapOf("root-1" to root.publicKeys.ed25519))

        val verified = verifier.verify(signed, Region.RU, "2026Q3", 7u, 1_750_000_000uL)
        assertEquals(keySet.keyId, verified.selected.keyId)

        assertFailsWith<IllegalArgumentException> {
            verifier.verify(signed, Region.EU, "2026Q3", 7u, 1_750_000_000uL)
        }
        val tampered = signed.copy(shardId = 8u)
        assertFailsWith<IllegalArgumentException> {
            verifier.verify(tampered, Region.RU, "2026Q3", 8u, 1_750_000_000uL)
        }
    }

    @Test
    fun fixedSeedDeviceKeysAreDeterministicAndSeparated() {
        val seed = ByteArray(32) { it.toByte() }
        val first = DeviceIdentity.fromSeed(seed).publicKeys
        val second = DeviceIdentity.fromSeed(seed).publicKeys

        assertContentEquals(first.x25519, second.x25519)
        assertContentEquals(first.ed25519, second.ed25519)
        assertFalse(first.x25519.contentEquals(first.ed25519))
    }

    @Test
    fun hybridEscrowBlobRecoversTheCommittedMessageKey() {
        val classicalEscrow = DeviceIdentity.fromSeed(ByteArray(32) { (0x40 + it).toByte() })
        val (mlKemPublic, mlKemSecret) = MLKEM.keyPair()
        val selected = EscrowKeySet(
            "hybrid-key",
            EscrowPublicKeys(classicalEscrow.publicKeys.x25519, mlKemPublic),
        )
        val config = VerifiedEscrowConfig(Region.RU, "2026Q3", 4u, selected)
        val messageKey = ByteArray(32) { (0x80 + it).toByte() }
        val commitment = KeyCommitments.derive(messageKey)

        val blob = HybridKodiumEscrowBlobBuilder().build(config, messageKey, commitment)
        val pqShared = assertNotNull(MLKEM.decapsulate(blob.mlKem768Ciphertext, mlKemSecret))
        val classicalShared = nacl.Box.beforenm(
            blob.ephemeralX25519PublicKey,
            classicalEscrow.privateKey.secretKey,
        )
        val escrowKey = HKDF.deriveSecrets(
            null,
            classicalShared + pqShared,
            "tima/escrow/v1".encodeToByteArray(),
            32,
        )

        assertContentEquals(
            messageKey,
            Kodium.decryptSymmetric(escrowKey, blob.symmetricKeyWrap).getOrThrow(),
        )
        assertContentEquals(commitment, blob.keyCommitment)
    }

    @Test
    fun mediaOnlyDocumentRoundTripsWithPresenceBitmapTwelve() {
        val sender = DeviceIdentity.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val recipient = DeviceIdentity.fromSeed(ByteArray(32) { (it + 41).toByte() })
        val escrowIdentity = DeviceIdentity.fromSeed(ByteArray(32) { (it + 81).toByte() })
        val (mlKemPublic, _) = MLKEM.keyPair()
        val crypto = MessengerCrypto(HybridKodiumEscrowBlobBuilder())
        val markup = buildJsonObject {
            put("entities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "media")
                    put("media_id", "123e4567-e89b-12d3-a456-426614174000")
                    put("secret_ref", "media-1")
                })
            })
        }
        val secret = buildJsonObject {
            put("media-1", buildJsonObject { put("kind", "image") })
        }
        val document = PlainTextDocumentV2(
            markup = markup,
            secretMetadata = secret,
            metadata = DocumentMetadata(revisionNumber = 1uL),
        )
        val envelope = crypto.encryptDocument(
            sender,
            EnvelopeHeader(
                messageId = 7uL,
                revisionId = "00000000-0000-4000-8000-000000000007",
                parentRevisionId = null,
                chatId = "00000000-0000-4000-8000-000000000008",
                senderId = "00000000-0000-4000-8000-000000000009",
                senderDeviceId = "00000000-0000-4000-8000-000000000010",
                messageKeyId = 0u,
            ),
            document,
            mapOf("00000000-0000-4000-8000-000000000011" to recipient.publicKeys),
            VerifiedEscrowConfig(
                Region.RU,
                "2026Q3",
                1u,
                EscrowKeySet(
                    "media-test",
                    EscrowPublicKeys(escrowIdentity.publicKeys.x25519, mlKemPublic),
                ),
            ),
        )

        assertEquals(12u, envelope.document.presenceBitmap)
        assertEquals(
            document,
            crypto.decryptDocumentViaPathB(
                "00000000-0000-4000-8000-000000000011",
                recipient,
                sender.publicKeys,
                envelope,
            ),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

}
