package com.tima.client.crypto

import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import io.kodium.Kodium
import io.kodium.core.MLKEM
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Test/development-only pin matching server key id dev-ed25519-1.
 * This is the public verification key; no private seed or signing material is present.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object DevelopmentEscrowTrustRoot {
    const val KEY_ID: String = "dev-ed25519-1"
    const val PUBLIC_KEY_BASE64: String = "IsUwucyZS8S/MjltIw/P+N+35bWEPJ4YMkpAWi9tHC8="

    val publicKey: ByteArray
        get() = Base64.Default.decode(PUBLIC_KEY_BASE64)
}

class DevelopmentEscrowTrustRootTest {
    @Test
    fun pinsExactServerPublicFixtureAndRejectsAnotherSigner() {
        assertEquals(
            "22c530b9cc994bc4bf32396d230fcff8dfb7e5b5843c9e18324a405a2f6d1c2f",
            DevelopmentEscrowTrustRoot.publicKey.toHex(),
        )

        val attacker = DeviceIdentity.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val unsigned = SignedEscrowConfig(
            configVersion = 1u,
            region = Region.RU,
            epochId = "2026Q3",
            shardId = 0u,
            validFromEpochSeconds = 1_750_000_000uL,
            validFromNanos = 0u,
            validUntilEpochSeconds = 1_800_000_000uL,
            validUntilNanos = 0u,
            current = EscrowKeySet(
                "RU-2026Q3-0",
                EscrowPublicKeys(ByteArray(32) { 7 }, ByteArray(MLKEM.PublicKeySize) { 9 }),
            ),
            next = null,
            signingKeyId = DevelopmentEscrowTrustRoot.KEY_ID,
            signature = ByteArray(0),
        )
        val attackerSignature = Kodium.signDetached(
            attacker.privateKey,
            CanonicalEncoding.escrowConfigInput(unsigned),
        ).getOrThrow()
        val verifier = EscrowConfigVerifier(
            mapOf(DevelopmentEscrowTrustRoot.KEY_ID to DevelopmentEscrowTrustRoot.publicKey),
        )

        assertFailsWith<IllegalArgumentException> {
            verifier.verify(
                unsigned.copy(signature = attackerSignature),
                Region.RU,
                "2026Q3",
                0u,
                1_760_000_000uL,
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
