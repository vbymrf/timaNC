package com.tima.client.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tima.client.media.EncryptedMediaBlobStore
import com.tima.client.media.ExecutableMagic
import com.tima.client.media.MediaVariantName
import com.tima.client.media.NormalizedImageSet
import com.tima.client.media.NormalizedImageVariant
import com.tima.client.media.PlatformImageNormalizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class AndroidImageNormalizer : PlatformImageNormalizer {
    override suspend fun normalize(input: ByteArray): NormalizedImageSet {
        ExecutableMagic.rejectInput(input)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        require(bounds.outWidth in 1..20_000 && bounds.outHeight in 1..20_000) {
            "unsupported image dimensions"
        }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000L) {
            "decoded image exceeds pixel limit"
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(input, 0, input.size)) {
            "invalid or unsupported image"
        }
        require(decoded.width == bounds.outWidth && decoded.height == bounds.outHeight) {
            "decoded dimensions differ from inspected bounds"
        }
        val variants = mutableListOf<NormalizedImageVariant>()
        try {
            MediaVariantName.entries.forEach { variants += normalize(decoded, it) }
            return NormalizedImageSet(variants)
        } catch (error: Throwable) {
            variants.forEach { it.jpeg.fill(0) }
            throw error
        } finally {
            decoded.recycle()
        }
    }

    private fun normalize(source: Bitmap, name: MediaVariantName): NormalizedImageVariant {
        val scale = minOf(1.0, name.maxEdge.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val resized = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val output = ByteArrayOutputStream()
        try {
            check(resized.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                "JPEG normalization failed"
            }
            return NormalizedImageVariant(name, output.toByteArray(), width, height)
        } finally {
            if (resized !== source) resized.recycle()
            output.reset()
            output.close()
        }
    }
}

class AndroidCiphertextBlobStore(context: Context) : EncryptedMediaBlobStore {
    private val root = File(context.filesDir, "private-media-cipher-v1").apply { mkdirs() }

    override suspend fun write(
        localId: String,
        name: MediaVariantName,
        ciphertext: ByteArray,
    ): String {
        require(localId.matches(Regex("^[A-Za-z0-9-]{1,80}$")))
        val id = "$localId-${name.wireValue}.bin"
        File(root, id).writeBytes(ciphertext)
        return id
    }

    override suspend fun read(blobId: String): ByteArray = file(blobId).readBytes()

    override suspend fun delete(blobId: String) {
        val value = file(blobId)
        if (value.exists()) {
            runCatching { value.writeBytes(ByteArray(value.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) }
            value.delete()
        }
    }

    override suspend fun clear() {
        root.listFiles().orEmpty().forEach { delete(it.name) }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("^[A-Za-z0-9-]{1,100}\\.bin$")))
        return File(root, id)
    }
}

internal fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
    require(maximumBytes > 0)
    val buffer = ByteArray(maximumBytes + 1)
    var total = 0
    try {
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            require(count > 0) { "input stream made no progress" }
            total += count
        }
        require(total <= maximumBytes && input.read() < 0) { "image exceeds 25 MiB" }
        return buffer.copyOf(total)
    } finally {
        buffer.fill(0)
    }
}
