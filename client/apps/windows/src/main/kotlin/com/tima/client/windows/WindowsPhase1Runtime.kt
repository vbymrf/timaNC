package com.tima.client.windows

import com.tima.client.network.AuthContext
import com.tima.client.network.ForegroundRealtimeSync
import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaRealtimeTransport
import com.tima.client.network.WakeSource
import com.tima.client.sync.WakeToSyncCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WindowsPhase1Runtime(baseUrl: String) : AutoCloseable {
    val storage = DpapiSecureStorage()

    private var auth: AuthContext? = null
    private val httpClient = HttpClient(CIO)
    private val validatedBaseUrl = validBaseUrl(baseUrl)
    private val transport = TimaHttpTransport(httpClient, validatedBaseUrl, { auth })
    private val realtime = TimaRealtimeTransport(httpClient, validatedBaseUrl, { auth })
    private var wakeSink: NotificationWakeSink? = null
    val linking = WindowsLinkingClient(transport, storage)

    fun installWakeCoordinator(
        gapFill: RestGapFill,
        reconnect: RealtimeReconnect,
    ): WakeToSyncCoordinator = WakeToSyncCoordinator(
        gapFill = gapFill,
        realtime = reconnect,
        nowMillis = System::currentTimeMillis,
    ).also { wakeSink = it }

    fun startPeriodicCatchUp(
        scope: CoroutineScope,
        intervalMillis: Long = 60_000,
    ): Job {
        require(intervalMillis >= 15_000) { "catch-up interval must be at least 15 seconds" }
        val coordinator = checkNotNull(wakeSink) { "wake coordinator is not installed" }
        return scope.launch {
            while (isActive) {
                try {
                    coordinator.wake(NotificationWakeSignal(WakeSource.PERIODIC_CATCH_UP))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A later periodic pass remains authoritative after transient failure.
                }
                delay(intervalMillis)
            }
        }
    }

    suspend fun runForegroundRealtime(
        subscriptionFrame: ByteArray,
        consumeFrame: suspend (ByteArray) -> Unit,
    ) {
        val coordinator = checkNotNull(wakeSink) { "wake coordinator is not installed" }
        ForegroundRealtimeSync(realtime, coordinator).run(subscriptionFrame, consumeFrame)
    }

    suspend fun restoreSession() {
        auth = linking.restoredSession()?.let { AuthContext(it.accessToken, it.deviceId) }
    }

    suspend fun claimLink(): LinkedWindowsSession =
        linking.claim().also { auth = AuthContext(it.accessToken, it.deviceId) }

    override fun close() {
        httpClient.close()
    }

    private fun validBaseUrl(value: String): String {
        require(value.startsWith("https://") || value.startsWith("http://localhost")) {
            "TIMA_API_BASE_URL must use HTTPS (localhost is allowed for development)"
        }
        return value
    }
}
