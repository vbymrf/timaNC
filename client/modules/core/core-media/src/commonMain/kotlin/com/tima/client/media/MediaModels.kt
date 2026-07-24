package com.tima.client.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val MEDIA_INPUT_LIMIT_BYTES: Int = 25 * 1024 * 1024
const val MEDIA_CIPHERTEXT_LIMIT_BYTES: Long = 100L * 1024 * 1024
const val NORMALIZED_IMAGE_MIME: String = "image/jpeg"

@Serializable
enum class MediaVariantName(val wireValue: String, val maxEdge: Int) {
    THUMBNAIL("thumbnail", 40),
    PREVIEW("preview", 320),
    FULL("full", 1280),
}

@Serializable
data class NormalizedImageVariant(
    val name: MediaVariantName,
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width in 1..name.maxEdge && height in 1..name.maxEdge)
        require(jpeg.isNotEmpty() && jpeg.size <= MEDIA_INPUT_LIMIT_BYTES)
        JpegPolicy.validate(jpeg, width, height, name.maxEdge)
    }
}

data class NormalizedImageSet(val variants: List<NormalizedImageVariant>) {
    init {
        require(variants.map { it.name } == MediaVariantName.entries) {
            "normalizer must return thumbnail, preview, and full exactly once in canonical order"
        }
    }
}

fun interface PlatformImageNormalizer {
    suspend fun normalize(input: ByteArray): NormalizedImageSet
}

@Serializable
data class MediaCipherVariant(
    val name: MediaVariantName,
    val ciphertext: ByteArray,
    val key: ByteArray,
    val sha256: String,
    val size: Long,
    val width: Int,
    val height: Int,
) {
    init {
        require(key.size == 32)
        require(ciphertext.size.toLong() == size && size in 1..MEDIA_CIPHERTEXT_LIMIT_BYTES)
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
}

data class PreparedPrivateImage(val variants: List<MediaCipherVariant>) {
    init {
        require(variants.map { it.name } == MediaVariantName.entries)
        require(variants.map { it.key.toList() }.distinct().size == variants.size) {
            "each media variant requires a separate key"
        }
    }

    fun wipeKeys() = variants.forEach { it.key.fill(0) }
}

@Serializable
data class MediaVariantSecret(
    val key: String,
    val sha256: String,
    val size: Long,
    val width: Int,
    val height: Int,
)

@Serializable
data class MediaAttachmentUi(
    val mediaId: String,
    val secretRef: String,
    val kind: String = "image",
    val mime: String = NORMALIZED_IMAGE_MIME,
    val thumbnail: MediaVariantSecret,
    val preview: MediaVariantSecret,
    val full: MediaVariantSecret,
)

object PrivateImageDocument {
    private val uuid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )
    private val base64 = Regex("^[A-Za-z0-9_-]{43}$")

    fun markup(mediaId: String, secretRef: String): JsonObject {
        require(uuid.matches(mediaId))
        require(secretRef.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        return buildJsonObject {
            put("entities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "media")
                    put("media_id", mediaId)
                    put("secret_ref", secretRef)
                })
            })
        }
    }

    fun isPrivateImageMarkup(markup: JsonObject?): Boolean = runCatching {
        if (markup?.keys != setOf("entities")) return@runCatching false
        val entities = markup.getValue("entities").jsonArray
        entities.size == 1 &&
            entities.single().jsonObject.let {
                it.keys == setOf("type", "media_id", "secret_ref") &&
                    it.string("type") == "media"
            }
    }.getOrDefault(false)

    fun secretMetadata(secretRef: String, image: MediaAttachmentUi): JsonObject =
        buildJsonObject {
            put(secretRef, buildJsonObject {
                put("kind", "image")
                put("mime", NORMALIZED_IMAGE_MIME)
                put("variants", buildJsonObject {
                    put("thumbnail", image.thumbnail.toJson())
                    put("preview", image.preview.toJson())
                    put("full", image.full.toJson())
                })
            })
        }

    fun parse(markup: JsonObject?, secretMetadata: JsonObject?): MediaAttachmentUi? {
        if (markup == null && secretMetadata == null) return null
        requireNotNull(markup) { "media metadata requires markup" }
        require(isPrivateImageMarkup(markup)) { "private image markup is malformed" }
        val entity = markup.getValue("entities").jsonArray.single().jsonObject
        val mediaId = entity.string("media_id")
        require(uuid.matches(mediaId))
        val secretRef = entity.string("secret_ref")
        val root = requireNotNull(secretMetadata)
        require(root.keys == setOf(secretRef))
        val secret = root.getValue(secretRef).jsonObject
        require(secret.keys == setOf("kind", "mime", "variants"))
        require(secret.string("kind") == "image")
        require(secret.string("mime") == NORMALIZED_IMAGE_MIME)
        val variants = secret.getValue("variants").jsonObject
        require(variants.keys == MediaVariantName.entries.map { it.wireValue }.toSet())
        return MediaAttachmentUi(
            mediaId = mediaId,
            secretRef = secretRef,
            thumbnail = variants.getValue("thumbnail").jsonObject.toSecret(MediaVariantName.THUMBNAIL),
            preview = variants.getValue("preview").jsonObject.toSecret(MediaVariantName.PREVIEW),
            full = variants.getValue("full").jsonObject.toSecret(MediaVariantName.FULL),
        )
    }

    private fun MediaVariantSecret.toJson() = buildJsonObject {
        put("key", key)
        put("sha256", sha256)
        put("size", size)
        put("width", width)
        put("height", height)
    }

    private fun JsonObject.toSecret(name: MediaVariantName): MediaVariantSecret {
        require(keys == setOf("key", "sha256", "size", "width", "height"))
        val result = MediaVariantSecret(
            key = string("key"),
            sha256 = string("sha256"),
            size = getValue("size").jsonPrimitive.content.toLong(),
            width = getValue("width").jsonPrimitive.intOrNull ?: error("width must be an integer"),
            height = getValue("height").jsonPrimitive.intOrNull ?: error("height must be an integer"),
        )
        require(base64.matches(result.key))
        require(result.sha256.matches(Regex("^[0-9a-f]{64}$")))
        require(result.size in 1..MEDIA_CIPHERTEXT_LIMIT_BYTES)
        require(result.width in 1..name.maxEdge && result.height in 1..name.maxEdge)
        return result
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("$name must be a string")
}

object JpegPolicy {
    const val SAFE_DECODE_PIXELS: Long = 4_000_000

    fun validate(bytes: ByteArray, width: Int, height: Int, maxEdge: Int) {
        require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte())
        require(bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        require(width in 1..maxEdge && height in 1..maxEdge)
        require(width.toLong() * height <= SAFE_DECODE_PIXELS)
        val actual = dimensions(bytes)
        require(actual.first == width && actual.second == height) {
            "JPEG dimensions differ from authenticated metadata"
        }
        require(!ExecutableMagic.matches(bytes)) { "executable input is blocked" }
    }

    fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
            "invalid JPEG magic"
        }
        var offset = 2
        while (offset + 3 < bytes.size) {
            require(bytes[offset] == 0xff.toByte()) { "malformed JPEG marker" }
            while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
            require(offset < bytes.size)
            val marker = bytes[offset++].toInt() and 0xff
            if (marker == 0xd9 || marker == 0xda) break
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            require(offset + 1 < bytes.size)
            val length = ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)
            require(length >= 2 && offset + length <= bytes.size) { "malformed JPEG segment" }
            if (marker in SOF_MARKERS) {
                require(length >= 7)
                val height = ((bytes[offset + 3].toInt() and 0xff) shl 8) or
                    (bytes[offset + 4].toInt() and 0xff)
                val width = ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                    (bytes[offset + 6].toInt() and 0xff)
                require(width > 0 && height > 0)
                return width to height
            }
            offset += length
        }
        error("JPEG has no supported frame header")
    }

    private val SOF_MARKERS = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
        0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
    )
}

object ExecutableMagic {
    fun matches(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (
            (bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) ||
                (bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) ||
                (bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte())
            )

    fun rejectInput(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MEDIA_INPUT_LIMIT_BYTES) {
            "image input is empty or exceeds 25 MiB"
        }
        require(!matches(bytes)) { "executable input is blocked" }
    }
}
