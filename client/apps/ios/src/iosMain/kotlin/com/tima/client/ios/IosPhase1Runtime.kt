package com.tima.client.ios

import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.AuthContext
import com.tima.client.network.Phase1PlatformClient
import com.tima.client.network.TimaHttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

class IosPhase1Runtime(baseUrl: String) {
    val secureStorage = KeychainSecureStorage()
    val appAttest = AppAttestProvider(secureStorage)
    val apns = ApnsPushTokenProvider(secureStorage)

    private var auth: AuthContext? = null
    private val httpClient = HttpClient(Darwin)
    private val transport = TimaHttpTransport(httpClient, validBaseUrl(baseUrl), { auth })
    val phase1 = Phase1PlatformClient(
        transport,
        AttestationCoordinator(transport, appAttest),
        apns,
    )

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
