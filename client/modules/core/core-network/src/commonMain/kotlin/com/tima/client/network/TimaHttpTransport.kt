package com.tima.client.network

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AuthContext(
    val accessToken: String,
    val deviceId: String,
)

class TimaTransportException(
    val status: Int,
    val code: String,
    val retryable: Boolean,
) : IllegalStateException("TIMA request failed: status=$status code=$code")

class TimaHttpTransport(
    private val client: HttpClient,
    baseUrl: String,
    private val auth: () -> AuthContext?,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun get(path: String): JsonObject =
        execute(HttpMethod.Get, path, null, null)

    suspend fun post(
        path: String,
        body: JsonObject? = null,
        idempotencyKey: String? = null,
    ): JsonObject = execute(HttpMethod.Post, path, body, idempotencyKey)

    suspend fun put(
        path: String,
        body: JsonObject,
        idempotencyKey: String? = null,
    ): JsonObject = execute(HttpMethod.Put, path, body, idempotencyKey)

    private suspend fun execute(
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        idempotencyKey: String?,
    ): JsonObject {
        require(path.startsWith("/") && !path.startsWith("//")) { "path must be absolute and host-relative" }
        val response = client.request(baseUrl + path) {
            this.method = method
            accept(ContentType.Application.Json)
            auth()?.let {
                header("Authorization", "Bearer ${it.accessToken}")
                header("X-Device-Id", it.deviceId)
            }
            idempotencyKey?.let { header("Idempotency-Key", it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val responseBody = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val problem = runCatching {
                json.parseToJsonElement(responseBody).jsonObject
                    .getValue("error").jsonObject
            }.getOrNull()
            throw TimaTransportException(
                status = response.status.value,
                code = problem?.get("code")?.jsonPrimitive?.content ?: "HTTP_${response.status.value}",
                retryable = response.status.value == 429 || response.status.value >= 500,
            )
        }
        if (responseBody.isBlank()) return buildJsonObject {}
        return json.parseToJsonElement(responseBody).jsonObject
    }
}
