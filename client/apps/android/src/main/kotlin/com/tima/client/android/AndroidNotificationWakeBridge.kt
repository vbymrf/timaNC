package com.tima.client.android

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.WakeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AndroidNotificationWakeBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = linkedMapOf<String, NotificationWakeSignal>()
    private var sink: NotificationWakeSink? = null

    fun install(value: NotificationWakeSink) {
        val queued = synchronized(lock) {
            sink = value
            pending.values.toList().also { pending.clear() }
        }
        queued.forEach { signal -> scope.launch { value.wake(signal) } }
    }

    fun uninstall(value: NotificationWakeSink) {
        synchronized(lock) {
            if (sink === value) sink = null
        }
    }

    fun wake(source: WakeSource, payload: Map<String, String> = emptyMap()) {
        val signal = NotificationWakeSignal(source, payload)
        val current = synchronized(lock) {
            sink ?: run {
                pending[signal.coalescingKey] = signal
                while (pending.size > MAX_PENDING_WAKES) {
                    pending.remove(pending.keys.first())
                }
                null
            }
        }
        current?.let { scope.launch { it.wake(signal) } }
    }

    private const val MAX_PENDING_WAKES = 32
}

/**
 * Called by a UnifiedPush connector/library after it has authenticated and
 * decoded the distributor delivery. No exported broadcast receiver is added.
 */
object UnifiedPushWakeAdapter {
    fun onMessage(payload: Map<String, String>) {
        AndroidNotificationWakeBridge.wake(WakeSource.UNIFIED_PUSH, payload)
    }
}

class TimaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        AndroidNotificationWakeBridge.wake(WakeSource.FCM, message.data)
    }
}
