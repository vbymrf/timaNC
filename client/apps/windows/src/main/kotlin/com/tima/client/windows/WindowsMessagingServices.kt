package com.tima.client.windows

import com.tima.client.crypto.DeviceIdentity
import com.tima.client.crypto.EscrowConfigVerifier
import com.tima.client.data.DeviceIdentityProvider
import com.tima.client.data.RecipientDeviceDirectory
import com.tima.client.data.SenderKeyDirectory
import com.tima.client.data.SessionRepository
import com.tima.client.data.VerifiedEscrowConfigProvider
import com.tima.client.domain.DevicePublicKeys
import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import com.tima.client.network.SecureStorage
import com.tima.client.network.TimaHttpTransport
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object WindowsDevelopmentModeGate {
    fun enabled(buildAllowsDevelopmentEscrow: Boolean, explicitEnvironmentOptIn: Boolean): Boolean =
        buildAllowsDevelopmentEscrow && explicitEnvironmentOptIn
}

/**
 * Reads the seed created by WindowsLinkingClient. A separate messaging identity is never created.
 * The seed bytes are wiped after each use and no decrypted identity is retained by this adapter.
 */
class WindowsDeviceIdentityRepository(
    private val storage: SecureStorage,
) : DeviceIdentityProvider {
    override suspend fun current(): DeviceIdentity {
        val seed = storage.read(WindowsLinkingClient.DEVICE_SEED)
            ?: error("Windows link identity is missing; relink this device")
        check(seed.size == 32) { "stored Windows device seed has an invalid length" }
        return try {
            DeviceIdentity.fromSeed(seed)
        } finally {
            seed.fill(0)
        }
    }
}

class WindowsTrustMaterialProvider(
    private val transport: TimaHttpTransport,
    private val sessions: SessionRepository,
    private val identities: WindowsDeviceIdentityRepository,
    private val developmentMode: Boolean,
) : RecipientDeviceDirectory, SenderKeyDirectory, VerifiedEscrowConfigProvider {
    override suspend fun devicesForChat(chatId: String): Map<String, DevicePublicKeys>? {
        val session = sessions.current() ?: return null
        val chat = transport.get("/v1/chats?limit=100").getValue("items").jsonArray
            .map { it.jsonObject }
            .singleOrNull { it.string("id") == chatId } ?: return null
        val peerId = chat.getValue("peer").jsonObject.string("id")
        return (bundles(session.userId) + bundles(peerId)).associate { it.deviceId to it.keys }
            .takeIf { it.containsKey(session.deviceId) }
    }

    override suspend fun keys(senderUserId: String, senderDeviceId: String): DevicePublicKeys? =
        bundles(senderUserId).singleOrNull { it.deviceId == senderDeviceId }?.keys

    override suspend fun forPrivateChat(chatId: String) =
        if (!developmentMode) {
            null
        } else {
            val epoch = currentQuarter()
            val signed = transport.get(
                "/v1/escrow/config?conversation_type=chat&conversation_id=$chatId&epoch=$epoch&shard=0",
            ).toSignedEscrow()
            val now = Instant.now()
            EscrowConfigVerifier(
                mapOf(DEVELOPMENT_ESCROW_SIGNER_ID to decode(DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY)),
            ).verify(
                config = signed,
                expectedRegion = Region.RU,
                expectedEpochId = epoch,
                expectedShardId = 0u,
                nowEpochSeconds = now.epochSecond.toULong(),
                nowNanos = now.nano.toUInt(),
            )
        }

    suspend fun identityReady(): Boolean = identities.current().publicKeys.let {
        it.x25519.size == 32 && it.ed25519.size == 32
    }

    private suspend fun bundles(userId: String): List<DeviceBundle> =
        transport.get("/v1/keys/bundle/$userId").getValue("bundles").jsonArray.map { raw ->
            val value = raw.jsonObject
            DeviceBundle(
                deviceId = value.string("device_id"),
                keys = DevicePublicKeys(
                    x25519 = decode(value.string("identity_key")),
                    ed25519 = decode(value.string("signing_identity_key")),
                ),
            )
        }

    private fun JsonObject.toSignedEscrow(): SignedEscrowConfig {
        val validFrom = Instant.parse(string("valid_from"))
        val validUntil = Instant.parse(string("valid_until"))
        val currentKeys = getValue("current_public_keys").jsonObject
        val nextKeys = getValue("next_public_keys").jsonObject
        return SignedEscrowConfig(
            configVersion = int("config_version").toUInt(),
            region = Region.valueOf(string("region")),
            epochId = string("epoch_id"),
            shardId = int("shard_id").toUInt(),
            validFromEpochSeconds = validFrom.epochSecond.toULong(),
            validFromNanos = validFrom.nano.toUInt(),
            validUntilEpochSeconds = validUntil.epochSecond.toULong(),
            validUntilNanos = validUntil.nano.toUInt(),
            current = EscrowKeySet(string("key_id"), currentKeys.toEscrowKeys()),
            next = EscrowKeySet(nextKeys.string("key_id"), nextKeys.toEscrowKeys()),
            signingKeyId = string("signer_key_id"),
            signature = decode(string("signature")),
        )
    }

    private fun JsonObject.toEscrowKeys() = EscrowPublicKeys(
        x25519Threshold = decode(string("x25519_threshold")),
        mlKem768 = decode(string("mlkem768")),
    )

    private fun currentQuarter(): String {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        return "%04dQ%d".format(now.year, (now.monthValue - 1) / 3 + 1)
    }

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private data class DeviceBundle(val deviceId: String, val keys: DevicePublicKeys)

    private companion object {
        const val DEVELOPMENT_ESCROW_SIGNER_ID = "dev-ed25519-1"
        const val DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY =
            "IsUwucyZS8S/MjltIw/P+N+35bWEPJ4YMkpAWi9tHC8="
    }
}

private fun JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.contentOrNull ?: error("$name is missing")

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
