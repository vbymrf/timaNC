package com.tima.client.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class TimaRealtimeTransport(
    private val client: HttpClient,
    baseUrl: String,
    private val auth: () -> AuthContext?,
    private val initialBackoffMillis: Long = 250,
    private val maxBackoffMillis: Long = 10_000,
) {
    private val websocketBaseUrl = when {
        baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
        baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
        else -> error("baseUrl must use http or https")
    }.trimEnd('/')

    fun binaryEvents(subscriptionFrame: ByteArray): Flow<ByteArray> = flow {
        require(subscriptionFrame.isNotEmpty()) { "protobuf subscription frame is required" }
        var backoff = initialBackoffMillis
        while (currentCoroutineContext().isActive) {
            val session = auth() ?: throw TimaTransportException(401, "UNAUTHORIZED", false)
            try {
                client.webSocket(
                    urlString = "$websocketBaseUrl/v1/ws?token=${session.accessToken.encodeURLParameter()}",
                    request = {
                        header("X-Device-Id", session.deviceId)
                        header("Sec-WebSocket-Protocol", "tima.pb.v1")
                    },
                ) {
                    send(Frame.Binary(true, subscriptionFrame))
                    backoff = initialBackoffMillis
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Binary -> emit(frame.readBytes())
                            is Frame.Close -> break
                            is Frame.Text -> throw IllegalStateException(
                                "realtime server violated binary protobuf protocol",
                            )
                            else -> Unit
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMillis)
            }
        }
    }
}
