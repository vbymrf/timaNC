package com.tima.client.crypto

import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
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

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

}
