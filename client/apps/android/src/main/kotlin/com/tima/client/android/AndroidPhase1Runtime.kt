package com.tima.client.android

import android.content.Context
import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.AuthContext
import com.tima.client.network.ForegroundRealtimeSync
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.Phase1PlatformClient
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaRealtimeTransport
import com.tima.client.sync.WakeToSyncCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

class AndroidPhase1Runtime(
    context: Context,
    baseUrl: String,
    cloudProjectNumber: Long,
) : AutoCloseable {
    val secureStorage = AndroidKeystoreSecureStorage(context)
    val integrity = PlayIntegrityAttestationProvider(context, cloudProjectNumber)
    val pushTokens = FcmPushTokenProvider(context)
    val unifiedPush = UnifiedPushEndpointProvider(context)

    private var auth: AuthContext? = null
    private val httpClient = HttpClient(OkHttp)
    private val validatedBaseUrl = validBaseUrl(baseUrl)
    private val transport = TimaHttpTransport(httpClient, validatedBaseUrl, { auth })
    private val realtime = TimaRealtimeTransport(httpClient, validatedBaseUrl, { auth })
    private var wakeSink: NotificationWakeSink? = null
    val phase1 = Phase1PlatformClient(
        transport,
        AttestationCoordinator(transport, integrity),
        pushTokens,
    )
    val unifiedPushPhase1 = Phase1PlatformClient(
        transport,
        AttestationCoordinator(transport, integrity),
        unifiedPush,
    )

    fun installWakeCoordinator(coordinator: NotificationWakeSink) {
        wakeSink?.let(AndroidNotificationWakeBridge::uninstall)
        wakeSink = coordinator
        AndroidNotificationWakeBridge.install(coordinator)
    }

    fun installWakeCoordinator(
        gapFill: RestGapFill,
        reconnect: RealtimeReconnect,
    ): WakeToSyncCoordinator = WakeToSyncCoordinator(
        gapFill = gapFill,
        realtime = reconnect,
        nowMillis = System::currentTimeMillis,
    ).also(::installWakeCoordinator)

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

    override fun close() {
        wakeSink?.let(AndroidNotificationWakeBridge::uninstall)
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
