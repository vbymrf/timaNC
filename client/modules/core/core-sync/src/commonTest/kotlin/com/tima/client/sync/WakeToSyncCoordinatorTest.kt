package com.tima.client.sync

import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.WakeSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WakeToSyncCoordinatorTest {
    @Test
    fun duplicateCollapseKeyRunsOneCatchUpWithinWindow() = runBlocking {
        var now = 1_000L
        val caughtUp = mutableListOf<Set<String>>()
        var reconnects = 0
        val coordinator = WakeToSyncCoordinator(
            gapFill = RestGapFill { caughtUp.add(it) },
            realtime = RealtimeReconnect { reconnects++ },
            nowMillis = { now },
        )
        val wake = messageWake()

        coordinator.wake(wake)
        now += 100
        coordinator.wake(wake.copy(source = WakeSource.UNIFIED_PUSH))

        assertEquals(listOf(setOf(CHAT_ID)), caughtUp)
        assertEquals(1, reconnects)
    }

    @Test
    fun appResumePerformsGlobalCatchUpAndReconnect() = runBlocking {
        val caughtUp = mutableListOf<Set<String>>()
        var reconnects = 0
        val coordinator = WakeToSyncCoordinator(
            gapFill = RestGapFill { caughtUp.add(it) },
            realtime = RealtimeReconnect { reconnects++ },
            nowMillis = { 1_000L },
        )

        coordinator.wake(NotificationWakeSignal(WakeSource.APP_RESUME))

        assertEquals(listOf(emptySet()), caughtUp)
        assertEquals(1, reconnects)
    }

    @Test
    fun realtimeFramesGapFillWithoutReconnectingActiveSocket() = runBlocking {
        val caughtUp = mutableListOf<Set<String>>()
        var reconnects = 0
        val coordinator = WakeToSyncCoordinator(
            gapFill = RestGapFill { caughtUp.add(it) },
            realtime = RealtimeReconnect { reconnects++ },
            nowMillis = { 1_000L },
        )

        coordinator.wake(realtimeWake("3:first"))
        coordinator.wake(realtimeWake("3:first"))
        coordinator.wake(realtimeWake("3:second"))

        assertEquals(listOf(emptySet(), emptySet()), caughtUp)
        assertEquals(0, reconnects)
    }

    private fun messageWake() = NotificationWakeSignal(
        source = WakeSource.FCM,
        payload = mapOf(
            "type" to "message",
            "chat_id" to CHAT_ID,
            "preview" to "Новое сообщение",
            "encrypted" to "true",
            "collapse_key" to "chat:$CHAT_ID",
        ),
    )

    private fun realtimeWake(id: String) = NotificationWakeSignal(
        source = WakeSource.REALTIME,
        transportCoalescingId = id,
    )

    private companion object {
        const val CHAT_ID = "7a20732a-f09b-4b1f-8f3d-787c80e09019"
    }
}
