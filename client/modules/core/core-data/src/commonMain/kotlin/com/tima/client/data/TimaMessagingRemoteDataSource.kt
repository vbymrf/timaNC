package com.tima.client.data

import com.tima.client.network.MessageReservationDto
import com.tima.client.network.PrivateDocumentEnvelopeDto
import com.tima.client.network.PrivateMessageHistoryDto
import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.PrivateMetadataDto
import com.tima.client.network.RestCryptoTransportAdapter
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.WrappedKeyDto
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

/**
 * Phase 1 private 1:1 REST adapter. Its write surface accepts encrypted envelopes only.
 */
class TimaMessagingRemoteDataSource(
    private val transport: TimaHttpTransport,
) : MessagingRemoteDataSource {
    override suspend fun listChats(limit: Int): List<RemoteChat> {
        require(limit in 1..100)
        return transport.get("/v1/chats?limit=$limit").array("items").map { raw ->
            raw.jsonObject.toRemoteChat()
        }
    }

    override suspend fun createChat(peerUserId: String, idempotencyKey: String): RemoteChat =
        transport.post(
            "/v1/chats",
            buildJsonObject { put("peer_user_id", peerUserId) },
            idempotencyKey,
        ).toRemoteChat()

    override suspend fun history(chatId: String, cursor: String?, limit: Int): RemoteHistoryPage {
        require(limit in 1..100)
        val path = buildString {
            append("/v1/chats/")
            append(chatId)
            append("/messages?limit=")
            append(limit)
            cursor?.let {
                append("&cursor=")
                append(urlEncode(it))
            }
        }
        val response = transport.get(path)
        return RemoteHistoryPage(
            items = response.array("items").map { it.jsonObject.toHistoryDto() },
            nextCursor = response["next_cursor"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun reserveMessage(chatId: String, idempotencyKey: String) =
        transport.post(
            "/v1/chats/$chatId/message-reservations",
            idempotencyKey = idempotencyKey,
        ).let {
            RestCryptoTransportAdapter.reservation(
                MessageReservationDto(
                    message_id = it.string("message_id"),
                    revision_id = it.string("revision_id"),
                    expires_at = it.string("expires_at"),
                ),
            )
        }

    override suspend fun send(
        chatId: String,
        value: PrivateMessageWriteDto,
        idempotencyKey: String,
    ) {
        transport.post("/v1/chats/$chatId/messages", value.toJson(), idempotencyKey)
    }

    override suspend fun edit(
        chatId: String,
        messageId: ULong,
        value: PrivateMessageWriteDto,
        idempotencyKey: String,
    ) {
        transport.post(
            "/v1/chats/$chatId/messages/$messageId/revisions",
            value.toJson(),
            idempotencyKey,
        )
    }

    override suspend fun markRead(chatId: String, messageId: ULong) {
        transport.post(
            "/v1/chats/$chatId/read",
            buildJsonObject { put("message_id", RestCryptoTransportAdapter.encodeDecimalInt64(messageId)) },
        )
    }

    override suspend fun delete(chatId: String, messageId: ULong) {
        transport.delete("/v1/chats/$chatId/messages/$messageId")
    }

    private fun JsonObject.toRemoteChat(): RemoteChat {
        val peer = getValue("peer").jsonObject
        return RemoteChat(
            id = string("id"),
            peerUserId = peer.string("id"),
            peerDisplayName = peer.string("display_name"),
            lastMessageAt = this["last_message_at"]?.jsonPrimitive?.contentOrNull,
            unreadCount = int("unread_count"),
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
            parent_revision_id = this["parent_revision_id"]?.jsonPrimitive?.contentOrNull,
            created_at = string("created_at"),
            document = PrivateDocumentEnvelopeDto(
                encrypted_nodes = document["encrypted_nodes"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }.orEmpty(),
                markup = document["markup"]?.jsonObject,
                encrypted_metadata = document["encrypted_metadata"]?.jsonPrimitive?.contentOrNull,
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
            wrapped_keys = array("wrapped_keys").map { wrapped ->
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
        putJsonObject("document") {
            if (document.encrypted_nodes.isNotEmpty()) {
                putJsonArray("encrypted_nodes") {
                    document.encrypted_nodes.forEach { add(JsonPrimitive(it)) }
                }
            }
            document.markup?.let { put("markup", it) }
            document.encrypted_metadata?.let { put("encrypted_metadata", it) }
            putJsonObject("metadata") {
                put("content_mode", document.metadata.content_mode)
                put("format_version", document.metadata.format_version.toInt())
                put("revision_number", document.metadata.revision_number.toLong())
            }
            put("protocol_version", document.protocol_version)
            put("presence_bitmap", document.presence_bitmap.toInt())
            put("key_commitment", document.key_commitment)
            put("escrow_blob", document.escrow_blob)
            document.ratchet_envelope?.let { put("ratchet_envelope", it) }
            put("signature", document.signature)
        }
        putJsonArray("wrapped_keys") {
            wrapped_keys.forEach {
                add(buildJsonObject {
                    put("device_id", it.device_id)
                    put("wrapped_key", it.wrapped_key)
                    put("protocol_version", it.protocol_version)
                    put("key_commitment", it.key_commitment)
                })
            }
        }
    }

    private fun JsonObject.array(name: String) = getValue(name).jsonArray
    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long

    private fun urlEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if (
                unsigned in 'a'.code..'z'.code ||
                unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code ||
                unsigned == '-'.code || unsigned == '_'.code ||
                unsigned == '.'.code || unsigned == '~'.code
            ) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }
}
