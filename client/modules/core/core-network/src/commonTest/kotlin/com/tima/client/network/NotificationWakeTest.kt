package com.tima.client.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotificationWakeTest {
    @Test
    fun acceptsGenericEncryptedMessageWake() {
        val signal = NotificationWakeSignal(
            source = WakeSource.UNIFIED_PUSH,
            payload = genericPayload(),
        )

        assertEquals(CHAT_ID, GenericWakePayloadPolicy.validatedChatId(signal))
        assertEquals("chat:$CHAT_ID", signal.coalescingKey)
    }

    @Test
    fun rejectsUnexpectedPrivatePayloadFields() {
        val signal = NotificationWakeSignal(
            source = WakeSource.FCM,
            payload = genericPayload() + ("sender_name" to "private"),
        )

        assertFailsWith<IllegalArgumentException> {
            GenericWakePayloadPolicy.validatedChatId(signal)
        }
    }

    @Test
    fun rejectsPreviewThatCouldContainPrivatePlaintext() {
        assertFailsWith<IllegalArgumentException> {
            GenericWakePayloadPolicy.validatedChatId(
                NotificationWakeSignal(
                    source = WakeSource.FCM,
                    payload = genericPayload() + ("preview" to "Alice: secret text"),
                ),
            )
        }
    }

    @Test
    fun realtimeFrameIdsCoalesceDuplicatesButSeparateDistinctFrames() {
        val first = frameCoalescingId(byteArrayOf(1, 2, 3))
        val duplicate = frameCoalescingId(byteArrayOf(1, 2, 3))
        val distinct = frameCoalescingId(byteArrayOf(1, 2, 4))

        assertEquals(first, duplicate)
        kotlin.test.assertNotEquals(first, distinct)
    }

    @Test
    fun lifecycleWakeCannotSmugglePayloadState() {
        assertFailsWith<IllegalArgumentException> {
            GenericWakePayloadPolicy.validatedChatId(
                NotificationWakeSignal(
                    WakeSource.APP_RESUME,
                    mapOf("chat_id" to CHAT_ID),
                ),
            )
        }
    }

    private fun genericPayload() = mapOf(
        "type" to "message",
        "chat_id" to CHAT_ID,
        "preview" to "Новое сообщение",
        "encrypted" to "true",
        "collapse_key" to "chat:$CHAT_ID",
    )

    private companion object {
        const val CHAT_ID = "7a20732a-f09b-4b1f-8f3d-787c80e09019"
    }
}
