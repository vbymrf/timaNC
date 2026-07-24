package com.tima.client.network

enum class WakeSource {
    FCM,
    UNIFIED_PUSH,
    APNS,
    REALTIME,
    APP_RESUME,
    PERIODIC_CATCH_UP,
}

data class NotificationWakeSignal(
    val source: WakeSource,
    val payload: Map<String, String> = emptyMap(),
    val transportCoalescingId: String? = null,
) {
    init {
        require(
            transportCoalescingId == null ||
                (source == WakeSource.REALTIME &&
                    transportCoalescingId.isNotBlank() &&
                    transportCoalescingId.length <= 128),
        ) { "transport coalescing identifiers are reserved for realtime wakes" }
    }

    val coalescingKey: String
        get() = transportCoalescingId?.let { "transport:${source.name}:$it" }
            ?: payload["collapse_key"]
            ?: payload["event_id"]
            ?: source.name
}

fun interface NotificationWakeSink {
    suspend fun wake(signal: NotificationWakeSignal)
}

fun interface RestGapFill {
    suspend fun catchUp(chatIds: Set<String>)
}

fun interface RealtimeReconnect {
    suspend fun reconnect()
}

object GenericWakePayloadPolicy {
    private val allowedKeys = setOf(
        "type",
        "chat_id",
        "preview",
        "encrypted",
        "collapse_key",
        "event_id",
    )
    private val uuid = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
    )

    fun validatedChatId(signal: NotificationWakeSignal): String? {
        if (signal.source in setOf(
                WakeSource.REALTIME,
                WakeSource.APP_RESUME,
                WakeSource.PERIODIC_CATCH_UP,
            )
        ) {
            require(signal.payload.isEmpty()) {
                "${signal.source} wake must not carry notification state"
            }
            return null
        }

        val payload = signal.payload
        require(payload.keys.all(allowedKeys::contains)) { "wake payload contains private or unknown fields" }
        require(payload["type"] == "message") { "wake payload type must be message" }
        require(payload["preview"] == "Новое сообщение") {
            "wake payload preview must be the generic private-message label"
        }
        require(payload["encrypted"] == "true") { "message wake must be marked encrypted" }
        val chatId = payload["chat_id"].orEmpty()
        require(uuid.matches(chatId)) { "wake payload chat_id must be a UUID" }
        require(payload["collapse_key"] == "chat:$chatId") {
            "wake payload collapse_key must match chat_id"
        }
        payload["event_id"]?.let {
            require(it.isNotBlank() && it.length <= 256) { "wake payload event_id is invalid" }
        }
        return chatId
    }
}
