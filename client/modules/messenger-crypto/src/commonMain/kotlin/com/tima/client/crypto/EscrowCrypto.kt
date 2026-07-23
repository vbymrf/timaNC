package com.tima.client.crypto

import com.tima.client.domain.EscrowBlob
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import com.tima.client.domain.VerifiedEscrowConfig
import io.kodium.Kodium
import io.kodium.core.MLKEM
import io.kodium.core.nacl
import io.kodium.ratchet.HKDF

class EscrowConfigVerifier(
    private val pinnedSigningRoots: Map<String, ByteArray>,
) {
    fun verify(
        config: SignedEscrowConfig,
        expectedRegion: Region,
        expectedEpochId: String,
        expectedShardId: UInt,
        nowEpochSeconds: ULong,
        nowNanos: UInt = 0u,
        requestedKeyId: String = config.current.keyId,
    ): VerifiedEscrowConfig {
        require(config.configVersion == 1u) { "unsupported escrow config version" }
        require(nowNanos <= 999_999_999u) { "current time nanos out of range" }
        require(config.region == expectedRegion) { "cross-region escrow config" }
        require(config.epochId == expectedEpochId) { "wrong escrow epoch" }
        require(config.shardId == expectedShardId) { "wrong escrow shard" }
        require(compareTime(
            nowEpochSeconds, nowNanos, config.validFromEpochSeconds, config.validFromNanos,
        ) >= 0) { "escrow config not yet valid" }
        require(compareTime(
            nowEpochSeconds, nowNanos, config.validUntilEpochSeconds, config.validUntilNanos,
        ) < 0) { "expired escrow config" }
        val root = pinnedSigningRoots[config.signingKeyId]
            ?: error("untrusted escrow signing key")
        require(config.signature.size == 64) { "missing escrow config signature" }
        require(CanonicalEd25519.isCanonical(config.signature)) { "non-canonical escrow signature" }
        require(Kodium.verifyDetached(root, CanonicalEncoding.escrowConfigInput(config), config.signature)) {
            "invalid escrow config signature"
        }

        val selected = sequenceOf(config.current, config.next)
            .filterNotNull()
            .firstOrNull { it.keyId == requestedKeyId }
            ?: error("unknown escrow key")
        require(selected.publicKeys.x25519Threshold.size == 32) { "invalid escrow X25519 key" }
        require(selected.publicKeys.mlKem768.size == MLKEM.PublicKeySize) { "invalid escrow ML-KEM key" }
        return VerifiedEscrowConfig(config.region, config.epochId, config.shardId, selected)
    }

    private fun compareTime(
        leftSeconds: ULong,
        leftNanos: UInt,
        rightSeconds: ULong,
        rightNanos: UInt,
    ): Int = if (leftSeconds != rightSeconds) {
        leftSeconds.compareTo(rightSeconds)
    } else {
        leftNanos.compareTo(rightNanos)
    }
}

interface EscrowBlobBuilder {
    fun build(
        config: VerifiedEscrowConfig,
        messageKey: ByteArray,
        keyCommitment: ByteArray,
    ): EscrowBlob
}

class HybridKodiumEscrowBlobBuilder : EscrowBlobBuilder {
    override fun build(
        config: VerifiedEscrowConfig,
        messageKey: ByteArray,
        keyCommitment: ByteArray,
    ): EscrowBlob {
        require(messageKey.size == 32) { "message key must be 32 bytes" }
        require(keyCommitment.size == 32) { "key commitment must be 32 bytes" }
        val ephemeral = Kodium.generateKeyPair()
        val (pqSharedSecret, kemCiphertext) =
            MLKEM.encapsulate(config.selected.publicKeys.mlKem768)
        val classicalSharedSecret = nacl.Box.beforenm(
            config.selected.publicKeys.x25519Threshold,
            ephemeral.secretKey,
        )
        val escrowKey = HKDF.deriveSecrets(
            salt = null,
            ikm = classicalSharedSecret + pqSharedSecret,
            info = "tima/escrow/v1".encodeToByteArray(),
            length = 32,
        )
        val symmetricWrap = try {
            Kodium.encryptSymmetric(escrowKey, messageKey).getOrThrow()
        } finally {
            classicalSharedSecret.fill(0)
            pqSharedSecret.fill(0)
            escrowKey.fill(0)
        }
        return EscrowBlob(
            region = config.region,
            epochId = config.epochId,
            shardId = config.shardId,
            keyId = config.selected.keyId,
            keyCommitment = keyCommitment.copyOf(),
            ephemeralX25519PublicKey = ephemeral.getPublicKey().encryptionKey.copyOf(),
            mlKem768Ciphertext = kemCiphertext,
            symmetricKeyWrap = symmetricWrap,
        )
    }
}

class FailClosedEscrowBlobBuilder : EscrowBlobBuilder {
    override fun build(
        config: VerifiedEscrowConfig,
        messageKey: ByteArray,
        keyCommitment: ByteArray,
    ): EscrowBlob = error("escrow is unavailable; private send is blocked")
}
