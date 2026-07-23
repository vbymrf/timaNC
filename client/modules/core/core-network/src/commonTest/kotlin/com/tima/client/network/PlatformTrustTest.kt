package com.tima.client.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformTrustTest {
    @Test
    fun genericPushContainsOnlyWakeupMetadata() {
        val decoded = GenericPushDecoder().decode(buildJsonObject {
            put("type", "message")
            put("chat_id", "chat-id")
            put("preview", "Новое сообщение")
            put("encrypted", true)
            put("collapse_key", "chat:chat-id")
        })
        assertEquals("chat-id", decoded.chatId)
    }

    @Test
    fun plaintextPushIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            GenericPushDecoder().decode(buildJsonObject {
                put("type", "message")
                put("chat_id", "chat-id")
                put("preview", "Новое сообщение")
                put("encrypted", true)
                put("collapse_key", "chat:chat-id")
                put("body", "secret message")
            })
        }
    }
}
