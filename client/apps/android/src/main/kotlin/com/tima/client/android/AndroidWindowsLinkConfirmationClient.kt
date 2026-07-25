package com.tima.client.android

import com.tima.client.network.TimaHttpTransport
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AndroidWindowsLinkConfirmationClient(
    private val transport: TimaHttpTransport,
    private val identities: AndroidDeviceIdentityRepository,
) {
    suspend fun confirm(qrPayload: String) {
        val values = parsePayload(qrPayload)
        val sessionId = values.required("session_id")
        val qrSecret = values.required("secret")
        val identityKey = Base64.getUrlDecoder().decode(values.required("identity_key"))
        val signingKey = Base64.getUrlDecoder().decode(values.required("signing_key"))
        require(identityKey.size == 32 && signingKey.size == 32) {
            "Windows link public keys have invalid lengths"
        }

        val wrappedDeviceSecret = ByteArray(48).also(SecureRandom()::nextBytes)
        val signingInput = MessageDigest.getInstance("SHA-256").run {
            update("TIMA-WINDOWS-LINK-v1".encodeToByteArray())
            update(byteArrayOf(0))
            update(sessionId.encodeToByteArray())
            update(byteArrayOf(0))
            update(identityKey)
            update(signingKey)
            update(wrappedDeviceSecret)
            digest()
        }
        val confirmation = identities.current().signDetached(signingInput)
        try {
            transport.post(
                "/v1/link/confirm",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("qr_secret", qrSecret)
                    put("confirmation", Base64.getEncoder().encodeToString(confirmation))
                    put(
                        "wrapped_device_secret",
                        Base64.getEncoder().encodeToString(wrappedDeviceSecret),
                    )
                },
            )
        } finally {
            wrappedDeviceSecret.fill(0)
            signingInput.fill(0)
            confirmation.fill(0)
        }
    }

    private fun parsePayload(value: String): Map<String, String> {
        val normalized = value.trim().let { input ->
            if (input.startsWith(HEX_PREFIX)) decodeHex(input.removePrefix(HEX_PREFIX)) else input
        }
        val uri = URI(normalized)
        require(uri.scheme == "tima" && uri.host == "link" && uri.path == "/v1") {
            "unsupported Windows link QR payload"
        }
        return uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) { "malformed Windows link QR payload" }
                decode(entry.substring(0, separator)) to decode(entry.substring(separator + 1))
            }
    }

    private fun Map<String, String>.required(name: String): String =
        get(name)?.takeIf(String::isNotBlank)
            ?: error("Windows link QR payload omitted $name")

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun decodeHex(value: String): String {
        require(value.length % 2 == 0 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "malformed hex Windows link payload"
        }
        return value.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .decodeToString()
    }

    private companion object {
        const val HEX_PREFIX = "hex:"
    }
}
