package com.tima.client.windows

import com.tima.client.network.AuthContext
import com.tima.client.network.TimaHttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

class WindowsPhase1Runtime(baseUrl: String) : AutoCloseable {
    val storage = DpapiSecureStorage()

    private var auth: AuthContext? = null
    private val httpClient = HttpClient(CIO)
    private val transport = TimaHttpTransport(httpClient, validBaseUrl(baseUrl), { auth })
    val linking = WindowsLinkingClient(transport, storage)

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
