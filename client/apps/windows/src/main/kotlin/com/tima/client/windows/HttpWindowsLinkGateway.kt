package com.tima.client.windows

import com.tima.client.platform.PlatformServiceUnavailable
import com.tima.client.platform.WindowsLinkChallenge
import com.tima.client.platform.WindowsLinkGateway
import com.tima.client.platform.WindowsLinkKeys
import com.tima.client.platform.WindowsLinkedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class HttpWindowsLinkGateway(
    baseUrl: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : WindowsLinkGateway {
    private val baseUri = URI(baseUrl.trimEnd('/')).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank()) {
            "Windows linking requires an HTTPS service URL"
        }
    }

    override suspend fun start(
        desktopName: String,
        keys: WindowsLinkKeys,
    ): WindowsLinkChallenge {
        val response = post(
            "/v1/link/session",
            buildJsonObject {
                put("desktop_public_key", Base64.getEncoder().encodeToString(keys.identityPublicKey))
                put("signing_public_key", Base64.getEncoder().encodeToString(keys.signingPublicKey))
                put("desktop_name", desktopName)
            }.toString(),
        )
        return WindowsLinkChallenge(
            sessionId = response.required("session_id"),
            qrPayload = response.required("qr_payload"),
            claimToken = response.required("claim_token"),
        )
    }

    override suspend fun claim(sessionId: String, claimToken: String): WindowsLinkedSession {
        val response = post(
            "/v1/link/claim",
            buildJsonObject {
                put("session_id", sessionId)
                put("claim_token", claimToken)
            }.toString(),
        )
        val wrapped = runCatching {
            Base64.getDecoder().decode(response.required("wrapped_device_secret"))
        }.getOrElse {
            throw PlatformServiceUnavailable("link service returned an invalid wrapped secret", it)
        }
        return WindowsLinkedSession(
            sessionId = sessionId,
            accessToken = response.required("access_token"),
            wrappedDeviceSecret = wrapped,
        )
    }

    private suspend fun post(path: String, body: String) = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = runCatching {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrElse {
            throw PlatformServiceUnavailable("Windows link service is unavailable", it)
        }
        if (response.statusCode() !in 200..299) {
            throw PlatformServiceUnavailable("Windows link service rejected the request")
        }
        runCatching { Json.parseToJsonElement(response.body()).jsonObject }
            .getOrElse { throw PlatformServiceUnavailable("Windows link service returned invalid JSON", it) }
    }

    private fun kotlinx.serialization.json.JsonObject.required(name: String): String =
        getValue(name).jsonPrimitive.content.takeIf(String::isNotBlank)
            ?: throw PlatformServiceUnavailable("link service omitted $name")
}
