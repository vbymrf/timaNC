package com.tima.client.sync

import com.tima.client.network.GenericWakePayloadPolicy
import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.WakeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes all push, realtime and lifecycle wake signals into authoritative
 * REST catch-up. Push and lifecycle wakes also recover the realtime
 * subscription; a frame delivered by the active socket never reconnects it.
 */
class WakeToSyncCoordinator(
    private val gapFill: RestGapFill,
    private val realtime: RealtimeReconnect,
    private val nowMillis: () -> Long,
    private val duplicateWindowMillis: Long = 5_000,
) : NotificationWakeSink {
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, PendingWake>()
    private val acceptedKeys = mutableSetOf<String>()
    private val recentlyHandled = mutableMapOf<String, Long>()
    private var activeDrain: CompletableDeferred<Unit>? = null

    override suspend fun wake(signal: NotificationWakeSignal) {
        val chatId = GenericWakePayloadPolicy.validatedChatId(signal)
        val key = signal.coalescingKey
        var ownsDrain = false
        val drain = mutex.withLock {
            val now = nowMillis()
            recentlyHandled.entries.removeAll { now - it.value >= duplicateWindowMillis }
            if (key !in acceptedKeys &&
                recentlyHandled[key]?.let { now - it < duplicateWindowMillis } != true
            ) {
                pending[key] = PendingWake(
                    chatId = chatId,
                    reconnectRequired = signal.source != WakeSource.REALTIME,
                )
                acceptedKeys += key
            }
            activeDrain ?: CompletableDeferred<Unit>().also {
                activeDrain = it
                ownsDrain = true
            }
        }
        if (!ownsDrain) {
            drain.await()
            return
        }

        try {
            drainPending(drain)
            drain.complete(Unit)
        } catch (error: Throwable) {
            mutex.withLock {
                pending.clear()
                acceptedKeys.clear()
                if (activeDrain === drain) activeDrain = null
            }
            drain.completeExceptionally(error)
            throw error
        }
    }

    private suspend fun drainPending(drain: CompletableDeferred<Unit>) {
        while (true) {
            val batch = mutex.withLock {
                if (pending.isEmpty()) {
                    if (activeDrain === drain) activeDrain = null
                    null
                } else {
                    pending.toMap().also { pending.clear() }
                }
            } ?: return
            gapFill.catchUp(batch.values.mapNotNull(PendingWake::chatId).toSet())
            if (batch.values.any(PendingWake::reconnectRequired)) {
                realtime.reconnect()
            }
            val handledAt = nowMillis()
            mutex.withLock {
                batch.keys.forEach {
                    recentlyHandled[it] = handledAt
                    acceptedKeys -= it
                }
            }
        }
    }

    private data class PendingWake(
        val chatId: String?,
        val reconnectRequired: Boolean,
    )
}
