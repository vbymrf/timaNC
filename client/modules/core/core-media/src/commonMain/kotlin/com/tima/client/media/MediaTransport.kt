package com.tima.client.media

import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaTransportException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException

@Serializable
data class MediaVariantManifest(
    val name: MediaVariantName,
    val contentType: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class PresignedUploadSlot(
    val variant: MediaVariantName,
    val url: String,
    val headers: Map<String, String>,
    val expiresAtEpochMillis: Long,
)

data class MediaUploadSlots(
    val mediaId: String,
    val uploads: List<PresignedUploadSlot>,
    val expiresAtEpochMillis: Long,
)
data class MediaAccessUrl(
    val mediaId: String,
    val variant: MediaVariantName,
    val url: String,
    val expiresAtEpochMillis: Long,
)

class MediaTransferException(
    val code: String,
    val retryable: Boolean,
    val refreshUpload: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(code, cause)

interface MediaTransfer {
    suspend fun initialize(
        chatId: String,
        idempotencyKey: String,
        manifest: List<MediaVariantManifest>,
    ): MediaUploadSlots
    suspend fun put(slot: PresignedUploadSlot, ciphertext: ByteArray)
    suspend fun complete(mediaId: String, idempotencyKey: String, manifest: List<MediaVariantManifest>)
    suspend fun access(mediaId: String, variant: MediaVariantName): MediaAccessUrl
    suspend fun download(value: MediaAccessUrl): ByteArray
}

class PrivateMediaTransport(
    private val api: TimaHttpTransport,
    private val presignedClient: HttpClient,
    private val allowLoopbackHttp: Boolean = false,
    private val nowEpochMillis: () -> Long,
) : MediaTransfer {
    override suspend fun initialize(
        chatId: String,
        idempotencyKey: String,
        manifest: List<MediaVariantManifest>,
    ): MediaUploadSlots {
        requireCanonicalManifest(manifest)
        val response = apiCall {
            api.post(
                "/v1/chats/$chatId/media/uploads",
                buildJsonObject {
                    put("kind", "image")
                    put("variants", buildJsonArray {
                        manifest.forEach { add(it.toJson()) }
                    })
                },
                idempotencyKey,
            )
        }
        return contract { MediaRestCodec.upload(response, nowEpochMillis()) }.also {
            it.uploads.forEach(::validateSlot)
        }
    }

    override suspend fun put(slot: PresignedUploadSlot, ciphertext: ByteArray) {
        validateSlot(slot, checkExpiry = false)
        val requested = validatePresignedUrl(slot.url, allowLoopbackHttp)
        require(ciphertext.isNotEmpty() && ciphertext.size.toLong() <= MEDIA_CIPHERTEXT_LIMIT_BYTES)
        if (slot.expiresAtEpochMillis <= nowEpochMillis()) {
            throw MediaTransferException("PRESIGNED_EXPIRED", true, refreshUpload = true)
        }
        val response = try {
            presignedClient.request(slot.url) {
                method = HttpMethod.Put
                slot.headers.forEach { (name, value) ->
                    require(value.length <= 4096 && value.none { char -> char == '\r' || char == '\n' })
                    header(name, value)
                }
                setBody(ciphertext)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MediaTransferException("PRESIGNED_NETWORK", true, cause = error)
        }
        require(response.call.request.url == requested) { "presigned redirects are forbidden" }
        when (response.status.value) {
            in 200..299 -> Unit
            401, 403 -> throw MediaTransferException("PRESIGNED_EXPIRED", true, true)
            429 -> throw MediaTransferException("PRESIGNED_RATE_LIMITED", true)
            in 500..599 -> throw MediaTransferException("PRESIGNED_SERVER", true)
            else -> throw MediaTransferException("PRESIGNED_HTTP_${response.status.value}", false)
        }
    }

    override suspend fun complete(mediaId: String, idempotencyKey: String, manifest: List<MediaVariantManifest>) {
        requireCanonicalManifest(manifest)
        val response = apiCall {
            api.post(
                "/v1/media/uploads/$mediaId/complete",
                buildJsonObject {
                    put("variants", buildJsonArray { manifest.forEach { add(it.toJson()) } })
                },
                idempotencyKey,
            )
        }
        contract { MediaRestCodec.completed(response, mediaId, manifest) }
    }

    override suspend fun access(mediaId: String, variant: MediaVariantName): MediaAccessUrl {
        val response = apiCall {
            api.post(
                "/v1/media/$mediaId/access",
                buildJsonObject { put("variant", variant.wireValue) },
            )
        }
        return contract {
            MediaRestCodec.access(response, mediaId, variant, nowEpochMillis())
        }.also {
            validatePresignedUrl(it.url, allowLoopbackHttp)
        }
    }

    override suspend fun download(value: MediaAccessUrl): ByteArray {
        val requested = validatePresignedUrl(value.url, allowLoopbackHttp)
        if (value.expiresAtEpochMillis <= nowEpochMillis()) {
            throw MediaTransferException("PRESIGNED_EXPIRED", true)
        }
        val response = try {
            presignedClient.request(value.url) { method = HttpMethod.Get }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MediaTransferException("PRESIGNED_NETWORK", true, cause = error)
        }
        require(response.call.request.url == requested) { "presigned redirects are forbidden" }
        when (response.status.value) {
            in 200..299 -> Unit
            401, 403 -> throw MediaTransferException("PRESIGNED_EXPIRED", true)
            429 -> throw MediaTransferException("PRESIGNED_RATE_LIMITED", true)
            in 500..599 -> throw MediaTransferException("PRESIGNED_SERVER", true)
            else -> throw MediaTransferException("PRESIGNED_HTTP_${response.status.value}", false)
        }
        val body = response.body<ByteArray>()
        require(body.isNotEmpty() && body.size.toLong() <= MEDIA_CIPHERTEXT_LIMIT_BYTES)
        return body
    }

    private fun MediaVariantManifest.toJson() = buildJsonObject {
        put("name", name.wireValue)
        put("content_type", contentType)
        put("size", size)
        put("sha256", sha256)
    }

    private fun validateSlot(slot: PresignedUploadSlot, checkExpiry: Boolean = true) {
        validatePresignedUrl(slot.url, allowLoopbackHttp)
        if (checkExpiry) require(slot.expiresAtEpochMillis > nowEpochMillis())
        slot.headers.forEach { (name, value) ->
            require(name.length in 1..100 && name.none(Char::isISOControl))
            require(name.lowercase() !in FORBIDDEN_HEADERS && !name.startsWith("Proxy-", true)) {
                "unsafe header is forbidden on presigned requests"
            }
            require(value.length <= 4096 && value.none { it == '\r' || it == '\n' })
        }
    }

    private suspend fun <T> apiCall(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TimaTransportException) {
        throw MediaTransferException(
            code = if (error.status == 401) "MEDIA_AUTH_REQUIRED" else error.code,
            retryable = error.status == 401 || error.status == 403 || error.retryable,
            cause = error,
        )
    } catch (error: Exception) {
        throw MediaTransferException("MEDIA_NETWORK", true, cause = error)
    }

    private fun <T> contract(block: () -> T): T = try {
        block()
    } catch (error: MediaTransferException) {
        throw error
    } catch (error: Exception) {
        throw MediaTransferException("MEDIA_CONTRACT_INVALID", false, cause = error)
    }

    private companion object {
        val FORBIDDEN_HEADERS = setOf(
            "authorization", "x-device-id", "host", "content-length", "transfer-encoding",
            "connection", "keep-alive", "te", "trailer", "upgrade",
        )
    }
}

object MediaRestCodec {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun upload(value: JsonObject, nowEpochMillis: Long): MediaUploadSlots {
        require(value.keys == setOf("media_id", "content_mode", "uploads", "expires_at"))
        val mediaId = value.string("media_id")
        require(uuid.matches(mediaId) && value.string("content_mode") == "private")
        val expiresAt = Rfc3339Utc.parseEpochMillis(value.string("expires_at"))
        require(expiresAt > nowEpochMillis) { "media upload response is already expired" }
        val slots = value.getValue("uploads").jsonArray.map { raw ->
            val slot = raw.jsonObject
            require(slot.keys == setOf("variant", "method", "url", "headers", "expires_at"))
            require(slot.string("method") == "PUT")
            val variant = variant(slot.string("variant"))
            val headers = slot.getValue("headers").jsonObject.mapValues {
                it.value.jsonPrimitive.contentOrNull ?: error("upload header must be a string")
            }
            val slotExpiry = Rfc3339Utc.parseEpochMillis(slot.string("expires_at"))
            require(slotExpiry > nowEpochMillis && slotExpiry <= expiresAt)
            PresignedUploadSlot(variant, slot.string("url"), headers, slotExpiry)
        }
        require(slots.map { it.variant } == MediaVariantName.entries)
        return MediaUploadSlots(mediaId, slots, expiresAt)
    }

    fun completed(value: JsonObject, mediaId: String, expected: List<MediaVariantManifest>) {
        require(value.keys == setOf("id", "kind", "content_mode", "status", "variants", "created_at"))
        require(value.string("id") == mediaId)
        require(value.string("kind") == "image" && value.string("content_mode") == "private")
        require(value.string("status") == "ready") { "private media completion must be ready" }
        val actual = value.getValue("variants").jsonArray.map { raw ->
            val item = raw.jsonObject
            require(item.keys == setOf("name", "content_type", "size", "sha256"))
            MediaVariantManifest(
                variant(item.string("name")),
                item.string("content_type"),
                item.getValue("size").jsonPrimitive.longOrNull ?: error("size must be integer"),
                item.string("sha256"),
            )
        }
        require(actual == expected) { "completed media manifest differs from requested ciphertext" }
    }

    fun access(
        value: JsonObject,
        mediaId: String,
        expected: MediaVariantName,
        nowEpochMillis: Long,
    ): MediaAccessUrl {
        require(value.keys == setOf("media_id", "variant", "url", "expires_at"))
        require(value.string("media_id") == mediaId)
        val variant = variant(value.string("variant"))
        require(variant == expected)
        val expiresAt = Rfc3339Utc.parseEpochMillis(value.string("expires_at"))
        require(expiresAt > nowEpochMillis)
        return MediaAccessUrl(mediaId, variant, value.string("url"), expiresAt)
    }

    private fun variant(value: String) =
        MediaVariantName.entries.singleOrNull { it.wireValue == value }
            ?: error("unsupported media variant")

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("$name must be a string")
}

internal object Rfc3339Utc {
    private val pattern = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$",
    )

    fun parseEpochMillis(value: String): Long {
        val match = requireNotNull(pattern.matchEntire(value)) { "timestamp must be RFC3339 UTC" }
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        require(year in 1970..9999 && month in 1..12 && day in 1..daysInMonth(year, month))
        require(hour in 0..23 && minute in 0..59 && second in 0..59)
        val millis = match.groupValues[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toInt()
        return daysFromCivil(year, month, day) * 86_400_000L +
            hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    // Howard Hinnant's civil-date conversion, offset to Unix epoch.
    private fun daysFromCivil(yearValue: Int, monthValue: Int, day: Int): Long {
        var year = yearValue
        if (monthValue <= 2) year--
        val era = if (year >= 0) year / 400 else (year - 399) / 400
        val yearOfEra = year - era * 400
        val month = monthValue + if (monthValue > 2) -3 else 9
        val dayOfYear = (153 * month + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return (era * 146097L + dayOfEra - 719468L)
    }
}

fun requireCanonicalManifest(value: List<MediaVariantManifest>) {
    require(value.map { it.name } == MediaVariantName.entries)
    value.forEach {
        require(it.contentType == "application/octet-stream")
        require(it.size in 1..MEDIA_CIPHERTEXT_LIMIT_BYTES)
        require(it.sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
}

fun validatePresignedUrl(value: String, allowLoopbackHttp: Boolean): Url {
    val url = Url(value)
    val loopback = allowLoopbackHttp && url.protocol.name == "http" &&
        url.host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")
    require(url.protocol.name == "https" || loopback) {
        "presigned media URL must use HTTPS; only explicit loopback development may use HTTP"
    }
    require(url.user == null && url.password == null && url.host.isNotBlank())
    return url
}
