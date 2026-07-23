package com.tima.client.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimaHttpTransportTest {
    @Test
    fun authenticatedIdempotentRequestUsesRequiredHeaders() = runBlocking {
        lateinit var request: HttpRequestData
        val engine = MockEngine {
            request = it
            respond(
                """{"id":"ok"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val transport = TimaHttpTransport(
            HttpClient(engine),
            "https://api.example.test",
            { AuthContext("token", "device") },
        )

        val response = transport.post(
            "/v1/chats",
            buildJsonObject { put("peer_user_id", "peer") },
            "00000000-0000-0000-0000-000000000001",
        )

        assertEquals("ok", response.getValue("id").toString().trim('"'))
        assertEquals("Bearer token", request.headers["Authorization"])
        assertEquals("device", request.headers["X-Device-Id"])
        assertEquals(
            "00000000-0000-0000-0000-000000000001",
            request.headers["Idempotency-Key"],
        )
    }

    @Test
    fun problemResponseMapsToTypedTransportErrorWithoutPayloadLogging() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"error":{"code":"IMMUTABLE_REVISION_CONFLICT","message":"conflict"}}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        val transport = TimaHttpTransport(HttpClient(engine), "https://api.example.test", { null })

        val error = assertFailsWith<TimaTransportException> {
            transport.get("/v1/chats")
        }
        assertEquals(409, error.status)
        assertEquals("IMMUTABLE_REVISION_CONFLICT", error.code)
    }
}
