package com.tima.client.integration

import com.tima.client.crypto.DeviceIdentity
import com.tima.client.crypto.EscrowConfigVerifier
import com.tima.client.crypto.HybridKodiumEscrowBlobBuilder
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.domain.DevicePublicKeys
import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.EscrowKeySet
import com.tima.client.domain.EscrowPublicKeys
import com.tima.client.domain.PlainTextDocumentV2
import com.tima.client.domain.Region
import com.tima.client.domain.SignedEscrowConfig
import com.tima.client.network.DevicePlatformDto
import com.tima.client.network.PrivateDocumentEnvelopeDto
import com.tima.client.network.PrivateMessageHistoryDto
import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.PrivateMetadataDto
import com.tima.client.network.RestCryptoTransportAdapter
import com.tima.client.network.WrappedKeyDto
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HttpTwoDeviceRoundTripTest {
    @Test
    fun aliceSendsAndBobFetchesAndDecryptsThroughHttpServer() {
        val baseUrl = System.getenv("TIMA_E2E_BASE_URL")?.trimEnd('/')
        if (baseUrl == null) {
            check(System.getenv("TIMA_REQUIRE_HTTP_E2E") != "true") {
                "TIMA_E2E_BASE_URL is required when TIMA_REQUIRE_HTTP_E2E=true"
            }
            return
        }

        val http = TestHttpClient(baseUrl)
        val aliceIdentity = DeviceIdentity.generate()
        val bobIdentity = DeviceIdentity.generate()
        val suffix = (System.currentTimeMillis() % 10_000_000).toString().padStart(7, '0')
        val alice = register(http, "+1555$suffix", "Alice", aliceIdentity, DevicePlatformDto.ANDROID)
        val bob = register(http, "+1666$suffix", "Bob", bobIdentity, DevicePlatformDto.WINDOWS)

        val chat = http.post(
            "/v1/chats",
            alice,
            buildJsonObject { put("peer_user_id", bob.userId) },
            idempotencyKey = UUID.randomUUID().toString(),
        )
        val chatId = chat.string("id")

        val bobBundles = http.get("/v1/keys/bundle/${bob.userId}", alice)
            .getValue("bundles").jsonArray
        assertEquals(1, bobBundles.size)
        val bobBundle = bobBundles.single().jsonObject
        assertEquals(bob.deviceId, bobBundle.string("device_id"))
        val bobDirectoryKeys = DevicePublicKeys(
            x25519 = decodeBase64(bobBundle.string("identity_key")),
            ed25519 = decodeBase64(bobBundle.string("signing_identity_key")),
        )

        val epoch = currentQuarter()
        val signedEscrow = parseEscrowConfig(
            http.get(
                "/v1/escrow/config?conversation_type=chat&conversation_id=$chatId&epoch=$epoch&shard=0",
                alice,
            ),
        )
        val now = Instant.now()
        val verifiedEscrow = EscrowConfigVerifier(
            mapOf(DEVELOPMENT_ESCROW_SIGNER_ID to decodeBase64(DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY)),
        ).verify(
            config = signedEscrow,
            expectedRegion = Region.RU,
            expectedEpochId = epoch,
            expectedShardId = 0u,
            nowEpochSeconds = now.epochSecond.toULong(),
            nowNanos = now.nano.toUInt(),
        )

        val reservationJson = http.post(
            "/v1/chats/$chatId/message-reservations",
            alice,
            body = null,
            idempotencyKey = UUID.randomUUID().toString(),
        )
        val reservation = RestCryptoTransportAdapter.reservation(
            com.tima.client.network.MessageReservationDto(
                message_id = reservationJson.string("message_id"),
                revision_id = reservationJson.string("revision_id"),
                expires_at = reservationJson.string("expires_at"),
            ),
        )
        val plaintext = PlainTextDocumentV2(
            textNodes = listOf("black-box hello from Alice", "fetched by Bob over HTTP"),
            metadata = DocumentMetadata(revisionNumber = 1uL),
        )
        val envelope = MessengerCrypto(HybridKodiumEscrowBlobBuilder()).encryptTextMessage(
            sender = aliceIdentity,
            header = EnvelopeHeader(
                messageId = reservation.messageId,
                revisionId = reservation.revisionId,
                parentRevisionId = null,
                chatId = chatId,
                senderId = alice.userId,
                senderDeviceId = alice.deviceId,
                messageKeyId = 0u,
            ),
            document = plaintext,
            recipientDevices = mapOf(
                alice.deviceId to aliceIdentity.publicKeys,
                bob.deviceId to bobDirectoryKeys,
            ),
            escrowConfig = verifiedEscrow,
            ratchetEnvelope = null,
        )
        val write = RestCryptoTransportAdapter.toTransport(envelope)
        val sendKey = UUID.randomUUID().toString()
        val sent = http.post(
            "/v1/chats/$chatId/messages",
            alice,
            write.toJson(),
            idempotencyKey = sendKey,
        )
        val replayed = http.post(
            "/v1/chats/$chatId/messages",
            alice,
            write.toJson(),
            idempotencyKey = sendKey,
        )
        assertEquals(sent.string("id"), replayed.string("id"))

        val historyResponse = http.get("/v1/chats/$chatId/messages?limit=10", bob)
        assertFalse(historyResponse.toString().contains("black-box hello from Alice"))
        assertFalse(historyResponse.toString().contains("fetched by Bob over HTTP"))
        val items = historyResponse.getValue("items").jsonArray
        assertEquals(1, items.size)
        val history = items.single().jsonObject.toHistoryDto()
        assertEquals(1, history.wrapped_keys.size)
        assertEquals(bob.deviceId, history.wrapped_keys.single().device_id)
        assertNull(history.document.ratchet_envelope)

        val aliceBundles = http.get("/v1/keys/bundle/${alice.userId}", bob)
            .getValue("bundles").jsonArray
        assertEquals(1, aliceBundles.size)
        val aliceBundle = aliceBundles.single().jsonObject
        assertEquals(alice.deviceId, aliceBundle.string("device_id"))
        val aliceDirectoryKeys = DevicePublicKeys(
            x25519 = decodeBase64(aliceBundle.string("identity_key")),
            ed25519 = decodeBase64(aliceBundle.string("signing_identity_key")),
        )
        val fetchedEnvelope = RestCryptoTransportAdapter.fromHistory(history)
        val decrypted = MessengerCrypto(HybridKodiumEscrowBlobBuilder()).decryptTextMessageViaPathB(
            recipientDeviceId = bob.deviceId,
            recipient = bobIdentity,
            senderPublicKeys = aliceDirectoryKeys,
            envelope = fetchedEnvelope,
        )
        assertEquals(plaintext, decrypted)
    }

    private fun register(
        http: TestHttpClient,
        phone: String,
        displayName: String,
        identity: DeviceIdentity,
        platform: DevicePlatformDto,
    ): Session {
        val challenge = http.post(
            "/v1/auth/register",
            session = null,
            body = buildJsonObject {
                put("phone", phone)
                put("locale", "en")
            },
        )
        val registration = RestCryptoTransportAdapter.deviceRegistration(
            name = "$displayName test device",
            platform = platform,
            publicKeys = identity.publicKeys,
            appVersion = "0.1.0-e2e",
        )
        val auth = http.post(
            "/v1/auth/verify",
            session = null,
            body = buildJsonObject {
                put("challenge_id", challenge.string("challenge_id"))
                put("otp", "000000")
                put("password", "phase1-black-box-password")
                put("display_name", displayName)
                putJsonObject("device") {
                    put("name", registration.name)
                    put("platform", registration.platform.wireValue)
                    put("identity_public_key", registration.identity_public_key)
                    put("signing_public_key", registration.signing_public_key)
                    registration.app_version?.let { put("app_version", it) }
                }
            },
        )
        return Session(
            accessToken = auth.string("access_token"),
            userId = auth.getValue("user").jsonObject.string("id"),
            deviceId = auth.getValue("device").jsonObject.string("id"),
        )
    }

    private fun parseEscrowConfig(value: JsonObject): SignedEscrowConfig {
        val validFrom = Instant.parse(value.string("valid_from"))
        val validUntil = Instant.parse(value.string("valid_until"))
        val currentKeys = value.getValue("current_public_keys").jsonObject
        val nextKeys = value.getValue("next_public_keys").jsonObject
        return SignedEscrowConfig(
            configVersion = value.int("config_version").toUInt(),
            region = Region.valueOf(value.string("region")),
            epochId = value.string("epoch_id"),
            shardId = value.int("shard_id").toUInt(),
            validFromEpochSeconds = validFrom.epochSecond.toULong(),
            validFromNanos = validFrom.nano.toUInt(),
            validUntilEpochSeconds = validUntil.epochSecond.toULong(),
            validUntilNanos = validUntil.nano.toUInt(),
            current = EscrowKeySet(
                value.string("key_id"),
                currentKeys.toEscrowPublicKeys(),
            ),
            next = EscrowKeySet(
                nextKeys.string("key_id"),
                nextKeys.toEscrowPublicKeys(),
            ),
            signingKeyId = value.string("signer_key_id"),
            signature = decodeBase64(value.string("signature")),
        )
    }

    private fun JsonObject.toHistoryDto(): PrivateMessageHistoryDto {
        val document = getValue("document").jsonObject
        val metadata = document.getValue("metadata").jsonObject
        return PrivateMessageHistoryDto(
            id = string("id"),
            conversation_id = string("conversation_id"),
            sender_id = string("sender_id"),
            sender_device_id = string("sender_device_id"),
            current_revision_id = string("current_revision_id"),
            message_key_id = int("message_key_id"),
            parent_revision_id = getValue("parent_revision_id").jsonPrimitive.contentOrNull,
            created_at = string("created_at"),
            document = PrivateDocumentEnvelopeDto(
                encrypted_nodes = document.getValue("encrypted_nodes").jsonArray.map { it.jsonPrimitive.content },
                metadata = PrivateMetadataDto(
                    content_mode = metadata.string("content_mode"),
                    format_version = metadata.int("format_version").toUInt(),
                    revision_number = metadata.long("revision_number").toULong(),
                ),
                protocol_version = document.int("protocol_version"),
                presence_bitmap = document.int("presence_bitmap").toUInt(),
                key_commitment = document.string("key_commitment"),
                escrow_blob = document.string("escrow_blob"),
                ratchet_envelope = document["ratchet_envelope"]?.jsonPrimitive?.contentOrNull,
                signature = document.string("signature"),
            ),
            wrapped_keys = getValue("wrapped_keys").jsonArray.map { wrapped ->
                wrapped.jsonObject.let {
                    WrappedKeyDto(
                        device_id = it.string("device_id"),
                        wrapped_key = it.string("wrapped_key"),
                        protocol_version = it.int("protocol_version"),
                        key_commitment = it.string("key_commitment"),
                    )
                }
            },
            deleted_at = this["deleted_at"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun PrivateMessageWriteDto.toJson(): JsonObject = buildJsonObject {
        put("sender_id", sender_id)
        put("message_id", message_id)
        put("revision_id", revision_id)
        put("message_key_id", message_key_id)
        put("document", document.toJson())
        putJsonArray("wrapped_keys") {
            wrapped_keys.forEach { add(it.toJson()) }
        }
    }

    private fun PrivateDocumentEnvelopeDto.toJson(): JsonObject = buildJsonObject {
        putJsonArray("encrypted_nodes") { encrypted_nodes.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("metadata") {
            put("content_mode", metadata.content_mode)
            put("format_version", metadata.format_version.toInt())
            put("revision_number", metadata.revision_number.toLong())
        }
        put("protocol_version", protocol_version)
        put("presence_bitmap", presence_bitmap.toInt())
        put("key_commitment", key_commitment)
        put("escrow_blob", escrow_blob)
        if (ratchet_envelope != null) put("ratchet_envelope", ratchet_envelope)
        put("signature", signature)
    }

    private fun WrappedKeyDto.toJson(): JsonObject = buildJsonObject {
        put("device_id", device_id)
        put("wrapped_key", wrapped_key)
        put("protocol_version", protocol_version)
        put("key_commitment", key_commitment)
    }

    private fun JsonObject.toEscrowPublicKeys(): EscrowPublicKeys = EscrowPublicKeys(
        x25519Threshold = decodeBase64(string("x25519_threshold")),
        mlKem768 = decodeBase64(string("mlkem768")),
    )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun currentQuarter(): String {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        return "%04dQ%d".format(now.year, (now.monthValue - 1) / 3 + 1)
    }

    private data class Session(
        val accessToken: String,
        val userId: String,
        val deviceId: String,
    )

    private class TestHttpClient(private val baseUrl: String) {
        private val client = HttpClient.newBuilder().build()

        fun get(path: String, session: Session): JsonObject =
            execute("GET", path, session, null, null)

        fun post(
            path: String,
            session: Session?,
            body: JsonObject?,
            idempotencyKey: String? = null,
        ): JsonObject = execute("POST", path, session, body, idempotencyKey)

        private fun execute(
            method: String,
            path: String,
            session: Session?,
            body: JsonObject?,
            idempotencyKey: String?,
        ): JsonObject {
            val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
            session?.let {
                builder.header("Authorization", "Bearer ${it.accessToken}")
                builder.header("X-Device-Id", it.deviceId)
            }
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "$method $path failed with ${response.statusCode()}: ${response.body()}"
            }
            if (response.body().isBlank()) return buildJsonObject {}
            return kotlinx.serialization.json.Json.parseToJsonElement(response.body()).jsonObject
        }
    }

    private companion object {
        const val DEVELOPMENT_ESCROW_SIGNER_ID = "dev-ed25519-1"
        const val DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY =
            "IsUwucyZS8S/MjltIw/P+N+35bWEPJ4YMkpAWi9tHC8="
    }
}
