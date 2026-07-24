@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package com.tima.client.ios

import com.tima.client.crypto.DeviceIdentity
import com.tima.client.crypto.EscrowConfigVerifier
import com.tima.client.data.ClientSession
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
import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.AttestationProof
import com.tima.client.network.AttestationProvider
import com.tima.client.network.SecureStorage
import com.tima.client.network.TimaHttpTransport
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.CoreCrypto.CC_LONG
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSUUID
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.io.encoding.Base64

class IosDeviceIdentityRepository(
    private val storage: SecureStorage,
) : DeviceIdentityProvider {
    private var cached: DeviceIdentity? = null

    override suspend fun current(): DeviceIdentity = cached ?: run {
        val stored = storage.read(IDENTITY_SEED)
        val seed = stored ?: ByteArray(32).also { value ->
            val status = value.usePinned {
                SecRandomCopyBytes(kSecRandomDefault, value.size.convert(), it.addressOf(0))
            }
            check(status == errSecSuccess) { "iOS secure random generation failed" }
            storage.write(IDENTITY_SEED, value)
        }
        try {
            DeviceIdentity.fromSeed(seed)
        } finally {
            seed.fill(0)
        }.also { cached = it }
    }

    private companion object {
        const val IDENTITY_SEED = "phase1.device-identity.seed.v1"
    }
}

class IosAuthenticationClient(
    private val transport: TimaHttpTransport,
    private val attestation: AttestationCoordinator,
    private val sessions: SessionRepository,
    private val identities: IosDeviceIdentityRepository,
    private val onSession: (ClientSession) -> Unit,
) {
    suspend fun register(
        phone: String,
        password: String,
        displayName: String,
        otp: String,
    ): ClientSession {
        require(otp.length == 6) { "a six-digit OTP is required" }
        val challenge = transport.post(
            "/v1/auth/register",
            buildJsonObject {
                put("phone", phone.trim())
                put("locale", "en")
            },
        )
        return authenticate(
            "/v1/auth/verify",
            "register",
            authBody(null, password, displayName, challenge.string("challenge_id"), otp),
        )
    }

    suspend fun login(phone: String, password: String): ClientSession =
        authenticate("/v1/auth/login", "login", authBody(phone.trim(), password, null, null, null))

    private suspend fun authBody(
        phone: String?,
        password: String,
        displayName: String?,
        challengeId: String?,
        otp: String?,
    ): JsonObject {
        require(password.length in 12..128) { "password must be 12–128 characters" }
        val keys = identities.current().publicKeys
        return buildJsonObject {
            phone?.let { put("phone", it) }
            challengeId?.let { put("challenge_id", it) }
            otp?.let { put("otp", it) }
            put("password", password)
            displayName?.takeIf(String::isNotBlank)?.let { put("display_name", it.trim()) }
            putJsonObject("device") {
                put("name", "Tima iOS")
                put("platform", "ios")
                put("identity_public_key", Base64.Default.encode(keys.x25519))
                put("signing_public_key", Base64.Default.encode(keys.ed25519))
                put("app_version", "0.1.0")
            }
        }
    }

    private suspend fun authenticate(path: String, action: String, body: JsonObject): ClientSession {
        val token = attestation.tokenFor(action, sha256(body.toString().encodeToByteArray()))
        val response = transport.post(path, body, headers = mapOf("X-Attestation-Token" to token))
        return ClientSession(
            accessToken = response.string("access_token"),
            userId = response.getValue("user").jsonObject.string("id"),
            deviceId = response.getValue("device").jsonObject.string("id"),
        ).also {
            sessions.save(it)
            onSession(it)
        }
    }
}

/**
 * Development-server fixture. The runtime constructs this only when both the Xcode DEBUG
 * configuration and the explicit TimaEnableDevelopmentAuth Info.plist flag are true.
 */
class DevelopmentIosAttestationProvider private constructor() : AttestationProvider {
    override suspend fun attest(action: String, requestBodySha256: ByteArray): AttestationProof {
        require(action == "register" || action == "login")
        require(requestBodySha256.size == 32)
        val keyId = "tima-ios-development"
        val proof = hmacSha256(
            DEVELOPMENT_SECRET,
            "ios".encodeToByteArray() + byteArrayOf(0) +
                keyId.encodeToByteArray() + byteArrayOf(0) + requestBodySha256,
        )
        return AttestationProof(
            endpoint = "/v1/verify/attestation/ios",
            body = buildJsonObject {
                put("key_id", keyId)
                put("assertion", Base64.Default.encode(proof))
                put("challenge", Base64.Default.encode(requestBodySha256))
            },
        )
    }

    companion object {
        fun create(debugBuild: Boolean, explicitDevelopmentAuth: Boolean): AttestationProvider {
            check(IosDevelopmentModeGate.enabled(debugBuild, explicitDevelopmentAuth)) {
                "development attestation is disabled"
            }
            return DevelopmentIosAttestationProvider()
        }

        private val DEVELOPMENT_SECRET = "development-attestation-key".encodeToByteArray()
    }
}

class IosTrustMaterialProvider(
    private val transport: TimaHttpTransport,
    private val sessions: SessionRepository,
    private val identities: IosDeviceIdentityRepository,
    private val developmentMode: Boolean,
) : RecipientDeviceDirectory, SenderKeyDirectory, VerifiedEscrowConfigProvider {
    override suspend fun devicesForChat(chatId: String): Map<String, DevicePublicKeys>? {
        val session = sessions.current() ?: return null
        val chat = transport.get("/v1/chats?limit=100").getValue("items").jsonArray
            .map { it.jsonObject }.singleOrNull { it.string("id") == chatId } ?: return null
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
            val timestamp = NSDate().secondsSinceEpoch()
            val wholeSeconds = timestamp.toLong()
            val nanos = ((timestamp - wholeSeconds) * 1_000_000_000).toUInt()
            EscrowConfigVerifier(
                mapOf(DEVELOPMENT_ESCROW_SIGNER_ID to Base64.Default.decode(DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY)),
            ).verify(signed, Region.RU, epoch, 0u, wholeSeconds.toULong(), nanos)
        }

    suspend fun identityReady(): Boolean = identities.current().publicKeys.let {
        it.x25519.size == 32 && it.ed25519.size == 32
    }

    private suspend fun bundles(userId: String): List<DeviceBundle> =
        transport.get("/v1/keys/bundle/$userId").getValue("bundles").jsonArray.map { raw ->
            val value = raw.jsonObject
            DeviceBundle(
                value.string("device_id"),
                DevicePublicKeys(
                    Base64.Default.decode(value.string("identity_key")),
                    Base64.Default.decode(value.string("signing_identity_key")),
                ),
            )
        }

    private fun JsonObject.toSignedEscrow(): SignedEscrowConfig {
        val formatter = NSISO8601DateFormatter()
        val validFrom = checkNotNull(formatter.dateFromString(string("valid_from")))
        val validUntil = checkNotNull(formatter.dateFromString(string("valid_until")))
        val currentKeys = getValue("current_public_keys").jsonObject
        val nextKeys = getValue("next_public_keys").jsonObject
        return SignedEscrowConfig(
            int("config_version").toUInt(),
            Region.valueOf(string("region")),
            string("epoch_id"),
            int("shard_id").toUInt(),
            validFrom.secondsSinceEpoch().toLong().toULong(),
            0u,
            validUntil.secondsSinceEpoch().toLong().toULong(),
            0u,
            EscrowKeySet(string("key_id"), currentKeys.toEscrowKeys()),
            EscrowKeySet(nextKeys.string("key_id"), nextKeys.toEscrowKeys()),
            string("signer_key_id"),
            Base64.Default.decode(string("signature")),
        )
    }

    private fun JsonObject.toEscrowKeys() = EscrowPublicKeys(
        Base64.Default.decode(string("x25519_threshold")),
        Base64.Default.decode(string("mlkem768")),
    )

    private fun currentQuarter(): String {
        val formatter = NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
            dateFormat = "yyyy-MM"
        }
        val text = formatter.stringFromDate(NSDate())
        val year = text.substringBefore("-").toInt()
        val month = text.substringAfter("-").toInt()
        return "${year}Q${(month - 1) / 3 + 1}"
    }

    private data class DeviceBundle(val deviceId: String, val keys: DevicePublicKeys)

    private companion object {
        const val DEVELOPMENT_ESCROW_SIGNER_ID = "dev-ed25519-1"
        const val DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY =
            "IsUwucyZS8S/MjltIw/P+N+35bWEPJ4YMkpAWi9tHC8="
    }
}

internal fun newUuid(): String = NSUUID().UUIDString.lowercase()

private fun sha256(value: ByteArray): ByteArray = ByteArray(CC_SHA256_DIGEST_LENGTH).also { output ->
    value.usePinned { inputPinned ->
        output.usePinned { outputPinned ->
            CC_SHA256(
                inputPinned.addressOf(0),
                value.size.convert<CC_LONG>(),
                outputPinned.addressOf(0).reinterpret(),
            )
        }
    }
}

private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray =
    ByteArray(CC_SHA256_DIGEST_LENGTH).also { output ->
        key.usePinned { keyPinned ->
            value.usePinned { valuePinned ->
                output.usePinned { outputPinned ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        keyPinned.addressOf(0),
                        key.size.convert(),
                        valuePinned.addressOf(0),
                        value.size.convert(),
                        outputPinned.addressOf(0),
                    )
                }
            }
        }
    }

private fun JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.contentOrNull ?: error("$name is missing")

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

private fun NSDate.secondsSinceEpoch(): Double =
    timeIntervalSinceReferenceDate + 978_307_200.0
