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
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
    fun aliceSendsAndBothBobDevicesFetchAndDecryptThroughHttpServer() {
        val baseUrl = System.getenv("TIMA_E2E_BASE_URL")?.trimEnd('/')
        if (baseUrl == null) {
            check(System.getenv("TIMA_REQUIRE_HTTP_E2E") != "true") {
                "TIMA_E2E_BASE_URL is required when TIMA_REQUIRE_HTTP_E2E=true"
            }
            return
        }

        val http = TestHttpClient(baseUrl)
        val aliceIdentity = DeviceIdentity.generate()
        val bobLaptopIdentity = DeviceIdentity.generate()
        val bobPhoneIdentity = DeviceIdentity.generate()
        val suffix = (System.currentTimeMillis() % 10_000_000).toString().padStart(7, '0')
        val bobPhone = "+1666$suffix"
        val alice = register(http, "+1555$suffix", "Alice", aliceIdentity, DevicePlatformDto.ANDROID)
        val bobLaptop = register(
            http, bobPhone, "Bob", bobLaptopIdentity, DevicePlatformDto.WINDOWS,
        )
        val bobMobile = login(
            http, bobPhone, "Bob mobile", bobPhoneIdentity, DevicePlatformDto.ANDROID,
        )
        assertEquals(bobLaptop.userId, bobMobile.userId)

        val chat = http.post(
            "/v1/chats",
            alice,
            buildJsonObject { put("peer_user_id", bobLaptop.userId) },
            idempotencyKey = UUID.randomUUID().toString(),
        )
        val chatId = chat.string("id")

        val bobBundles = http.get("/v1/keys/bundle/${bobLaptop.userId}", alice)
            .getValue("bundles").jsonArray
        assertEquals(2, bobBundles.size)
        val bobDirectoryKeys = bobBundles.associate { bundle ->
            val value = bundle.jsonObject
            value.string("device_id") to DevicePublicKeys(
                x25519 = decodeBase64(value.string("identity_key")),
                ed25519 = decodeBase64(value.string("signing_identity_key")),
            )
        }
        assertEquals(setOf(bobLaptop.deviceId, bobMobile.deviceId), bobDirectoryKeys.keys)

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
            recipientDevices = mapOf(alice.deviceId to aliceIdentity.publicKeys) + bobDirectoryKeys,
            escrowConfig = verifiedEscrow,
            ratchetEnvelope = null,
        )
        val write = RestCryptoTransportAdapter.toTransport(envelope)
        val realtime = RealtimeProbe.connect(baseUrl, bobLaptop, chatId)
        val sendKey = UUID.randomUUID().toString()
        val sent: JsonObject
        val replayed: JsonObject
        val signal: MessageCreatedSignal
        try {
            sent = http.post(
                "/v1/chats/$chatId/messages",
                alice,
                write.toJson(),
                idempotencyKey = sendKey,
            )
            replayed = http.post(
                "/v1/chats/$chatId/messages",
                alice,
                write.toJson(),
                idempotencyKey = sendKey,
            )
            signal = realtime.awaitMessageCreated()
        } finally {
            realtime.close()
        }
        assertEquals(sent.string("id"), replayed.string("id"))
        assertEquals(3, sent.getValue("wrapped_keys").jsonArray.size)
        assertEquals(chatId, signal.chatId)
        assertEquals(reservation.messageId, signal.messageId)

        val aliceBundles = http.get("/v1/keys/bundle/${alice.userId}", bobLaptop)
            .getValue("bundles").jsonArray
        assertEquals(1, aliceBundles.size)
        val aliceBundle = aliceBundles.single().jsonObject
        assertEquals(alice.deviceId, aliceBundle.string("device_id"))
        val aliceDirectoryKeys = DevicePublicKeys(
            x25519 = decodeBase64(aliceBundle.string("identity_key")),
            ed25519 = decodeBase64(aliceBundle.string("signing_identity_key")),
        )
        listOf(
            bobLaptop to bobLaptopIdentity,
            bobMobile to bobPhoneIdentity,
        ).forEach { (recipientSession, recipientIdentity) ->
            val historyResponse = http.get("/v1/chats/$chatId/messages?limit=10", recipientSession)
            assertFalse(historyResponse.toString().contains("black-box hello from Alice"))
            assertFalse(historyResponse.toString().contains("fetched by Bob over HTTP"))
            val items = historyResponse.getValue("items").jsonArray
            assertEquals(1, items.size)
            val history = items.single().jsonObject.toHistoryDto()
            assertEquals(1, history.wrapped_keys.size)
            assertEquals(recipientSession.deviceId, history.wrapped_keys.single().device_id)
            assertNull(history.document.ratchet_envelope)

            val fetchedEnvelope = RestCryptoTransportAdapter.fromHistory(history)
            val decrypted = MessengerCrypto(HybridKodiumEscrowBlobBuilder()).decryptTextMessageViaPathB(
                recipientDeviceId = recipientSession.deviceId,
                recipient = recipientIdentity,
                senderPublicKeys = aliceDirectoryKeys,
                envelope = fetchedEnvelope,
            )
            assertEquals(plaintext, decrypted)
        }
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
                put("password", TEST_PASSWORD)
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

    private fun login(
        http: TestHttpClient,
        phone: String,
        deviceName: String,
        identity: DeviceIdentity,
        platform: DevicePlatformDto,
    ): Session {
        val registration = RestCryptoTransportAdapter.deviceRegistration(
            name = deviceName,
            platform = platform,
            publicKeys = identity.publicKeys,
            appVersion = "0.1.0-e2e",
        )
        val auth = http.post(
            "/v1/auth/login",
            session = null,
            body = buildJsonObject {
                put("phone", phone)
                put("password", TEST_PASSWORD)
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

    private data class MessageCreatedSignal(
        val chatId: String,
        val messageId: ULong,
    )

    private class RealtimeProbe private constructor(
        private val socket: WebSocket,
        private val frames: LinkedBlockingQueue<Any>,
    ) {
        fun awaitMessageCreated(): MessageCreatedSignal =
            ProtoFrames.parseMessageCreated(awaitFrame())

        fun close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join()
        }

        private fun awaitFrame(): ByteArray {
            val value = frames.poll(10, TimeUnit.SECONDS)
                ?: error("timed out waiting for realtime frame")
            if (value is Throwable) throw AssertionError("realtime websocket failed", value)
            return value as ByteArray
        }

        companion object {
            fun connect(baseUrl: String, session: Session, chatId: String): RealtimeProbe {
                val frames = LinkedBlockingQueue<Any>()
                val listener = BinaryFrameListener(frames)
                val wsBase = when {
                    baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
                    baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
                    else -> error("unsupported realtime base URL")
                }
                val socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .subprotocols("tima.pb.v1")
                    .header("X-Device-Id", session.deviceId)
                    .buildAsync(URI.create("$wsBase/v1/ws?token=${session.accessToken}"), listener)
                    .join()
                check(socket.subprotocol == "tima.pb.v1") { "realtime protobuf subprotocol not selected" }
                socket.sendBinary(ByteBuffer.wrap(ProtoFrames.subscribeFrame(chatId)), true).join()
                val probe = RealtimeProbe(socket, frames)
                probe.awaitFrame() // subscription Ack
                return probe
            }
        }
    }

    private class BinaryFrameListener(
        private val frames: LinkedBlockingQueue<Any>,
    ) : WebSocket.Listener {
        private val current = ByteArrayOutputStream()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        @Synchronized
        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean,
        ): CompletionStage<*>? {
            val part = ByteArray(data.remaining())
            data.get(part)
            current.write(part)
            if (last) {
                frames.put(current.toByteArray())
                current.reset()
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            frames.offer(error)
        }
    }

    private class ProtoReader(private val value: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < value.size

        fun tag(): Int = varint().toInt()

        fun varint(): ULong {
            var result = 0uL
            var shift = 0
            while (shift < 64) {
                require(position < value.size) { "truncated protobuf varint" }
                val byte = value[position++].toInt() and 0xff
                result = result or ((byte and 0x7f).toULong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            error("invalid protobuf varint")
        }

        fun bytes(): ByteArray {
            val size = varint()
            require(size <= Int.MAX_VALUE.toULong()) { "protobuf field too large" }
            val end = position + size.toInt()
            require(end <= value.size) { "truncated protobuf bytes" }
            return value.copyOfRange(position, end).also { position = end }
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                2 -> bytes()
                else -> error("unsupported protobuf wire type $wireType")
            }
        }
    }

    private object ProtoFrames {
    fun subscribeFrame(chatId: String): ByteArray {
        val uuid = UUID.fromString(chatId)
        val uuidBytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val uuidMessage = protoBytesField(1, uuidBytes)
        val subscribe = protoBytesField(1, uuidMessage)
        return protoVarintField(1, 1uL) + protoBytesField(10, subscribe)
    }

    fun parseMessageCreated(frame: ByteArray): MessageCreatedSignal {
        val serverEvent = nestedField(frame, 10)
        val messageCreated = nestedField(serverEvent, 10)
        val revision = nestedField(messageCreated, 1)
        val chatUuid = nestedField(revision, 1)
        val chatBytes = nestedField(chatUuid, 1)
        val chatBuffer = ByteBuffer.wrap(chatBytes)
        val chatId = UUID(chatBuffer.long, chatBuffer.long).toString()
        val reader = ProtoReader(revision)
        var messageId: ULong? = null
        while (reader.hasRemaining()) {
            val tag = reader.tag()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 2 && wire == 0) {
                messageId = reader.varint()
            } else {
                reader.skip(wire)
            }
        }
        return MessageCreatedSignal(chatId, requireNotNull(messageId))
    }

    private fun nestedField(value: ByteArray, expectedField: Int): ByteArray {
        val reader = ProtoReader(value)
        while (reader.hasRemaining()) {
            val tag = reader.tag()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == expectedField && wire == 2) return reader.bytes()
            reader.skip(wire)
        }
        error("protobuf field $expectedField not found")
    }

    private fun protoVarintField(field: Int, value: ULong): ByteArray =
        protoVarint((field shl 3).toULong()) + protoVarint(value)

    private fun protoBytesField(field: Int, value: ByteArray): ByteArray =
        protoVarint(((field shl 3) or 2).toULong()) +
            protoVarint(value.size.toULong()) + value

    private fun protoVarint(value: ULong): ByteArray {
        var remaining = value
        val result = mutableListOf<Byte>()
        while (remaining >= 0x80u) {
            result += ((remaining and 0x7fuL).toInt() or 0x80).toByte()
            remaining = remaining shr 7
        }
        result += remaining.toByte()
        return result.toByteArray()
    }
    }

    private companion object {
        const val TEST_PASSWORD = "phase1-black-box-password"
        const val DEVELOPMENT_ESCROW_SIGNER_ID = "dev-ed25519-1"
        const val DEVELOPMENT_ESCROW_SIGNER_PUBLIC_KEY =
            "IsUwucyZS8S/MjltIw/P+N+35bWEPJ4YMkpAWi9tHC8="
    }
}
