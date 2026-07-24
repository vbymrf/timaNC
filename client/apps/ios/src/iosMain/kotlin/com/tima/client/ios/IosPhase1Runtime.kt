package com.tima.client.ios

import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.AuthContext
import com.tima.client.network.ForegroundRealtimeSync
import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.Phase1PlatformClient
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaRealtimeTransport
import com.tima.client.network.WakeSource
import com.tima.client.sync.WakeToSyncCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.time.TimeSource

class IosPhase1Runtime(baseUrl: String) {
    val secureStorage = KeychainSecureStorage()
    val appAttest = AppAttestProvider(secureStorage)
    val apns = ApnsPushTokenProvider(secureStorage)

    private var auth: AuthContext? = null
    private val httpClient = HttpClient(Darwin)
    private val validatedBaseUrl = validBaseUrl(baseUrl)
    private val transport = TimaHttpTransport(httpClient, validatedBaseUrl, { auth })
    private val realtime = TimaRealtimeTransport(httpClient, validatedBaseUrl, { auth })
    private var wakeSink: NotificationWakeSink? = null
    private val clockStart = TimeSource.Monotonic.markNow()
    val phase1 = Phase1PlatformClient(
        transport,
        AttestationCoordinator(transport, appAttest),
        apns,
    )

    fun installWakeCoordinator(
        gapFill: RestGapFill,
        reconnect: RealtimeReconnect,
    ): WakeToSyncCoordinator = WakeToSyncCoordinator(
        gapFill = gapFill,
        realtime = reconnect,
        nowMillis = { clockStart.elapsedNow().inWholeMilliseconds },
    ).also { wakeSink = it }

    suspend fun applicationDidBecomeActive() {
        checkNotNull(wakeSink) { "wake coordinator is not installed" }
            .wake(NotificationWakeSignal(WakeSource.APP_RESUME))
    }

    suspend fun didReceiveApnsWake(payload: Map<String, String>) {
        checkNotNull(wakeSink) { "wake coordinator is not installed" }
            .wake(NotificationWakeSignal(WakeSource.APNS, payload))
    }

    suspend fun runForegroundRealtime(
        subscriptionFrame: ByteArray,
        consumeFrame: suspend (ByteArray) -> Unit,
    ) {
        val coordinator = checkNotNull(wakeSink) { "wake coordinator is not installed" }
        ForegroundRealtimeSync(realtime, coordinator).run(subscriptionFrame, consumeFrame)
    }

    suspend fun restoreSession() {
        val token = secureStorage.read(ACCESS_TOKEN)?.decodeToString()
        val deviceId = secureStorage.read(DEVICE_ID)?.decodeToString()
        auth = if (!token.isNullOrBlank() && !deviceId.isNullOrBlank()) {
            AuthContext(token, deviceId)
        } else {
            null
        }
    }

    suspend fun persistSession(accessToken: String, deviceId: String) {
        require(accessToken.isNotBlank() && deviceId.isNotBlank())
        secureStorage.write(ACCESS_TOKEN, accessToken.encodeToByteArray())
        secureStorage.write(DEVICE_ID, deviceId.encodeToByteArray())
        auth = AuthContext(accessToken, deviceId)
    }

    fun close() {
        httpClient.close()
    }

    private fun validBaseUrl(value: String): String {
        require(value.startsWith("https://") || value.startsWith("http://localhost")) {
            "Tima API base URL must use HTTPS (localhost is allowed for development)"
        }
        return value
    }

    private companion object {
        const val ACCESS_TOKEN = "auth.access-token"
        const val DEVICE_ID = "auth.device-id"
    }
}
