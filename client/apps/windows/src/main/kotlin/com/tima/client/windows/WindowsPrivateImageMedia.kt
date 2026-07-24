package com.tima.client.windows

import com.tima.client.media.EncryptedMediaBlobStore
import com.tima.client.media.ExecutableMagic
import com.tima.client.media.MediaVariantName
import com.tima.client.media.NormalizedImageSet
import com.tima.client.media.NormalizedImageVariant
import com.tima.client.media.PlatformImageNormalizer
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class WindowsImageNormalizer : PlatformImageNormalizer {
    override suspend fun normalize(input: ByteArray): NormalizedImageSet {
        ExecutableMagic.rejectInput(input)
        val dimensions = ImageIO.createImageInputStream(ByteArrayInputStream(input)).use { imageInput ->
            val readers = ImageIO.getImageReaders(imageInput)
            require(readers.hasNext()) { "invalid or unsupported image" }
            readers.next().let { reader ->
                try {
                    reader.input = imageInput
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }
        require(dimensions.first in 1..20_000 && dimensions.second in 1..20_000)
        require(dimensions.first.toLong() * dimensions.second <= 80_000_000L)
        val source = ByteArrayInputStream(input).use { ImageIO.read(it) }
            ?: error("invalid or unsupported image")
        require(source.width == dimensions.first && source.height == dimensions.second)
        val variants = mutableListOf<NormalizedImageVariant>()
        try {
            MediaVariantName.entries.forEach { variants += normalize(source, it) }
            return NormalizedImageSet(variants)
        } catch (error: Throwable) {
            variants.forEach { it.jpeg.fill(0) }
            throw error
        }
    }

    private fun normalize(source: BufferedImage, name: MediaVariantName): NormalizedImageVariant {
        val scale = minOf(1.0, name.maxEdge.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also {
            it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            it.drawImage(source, 0, 0, width, height, null)
            it.dispose()
        }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "jpg", output)) { "JPEG encoder unavailable" }
        return NormalizedImageVariant(name, output.toByteArray(), width, height)
    }
}

class WindowsCiphertextBlobStore(private val root: Path) : EncryptedMediaBlobStore {
    init {
        Files.createDirectories(root)
    }

    override suspend fun write(
        localId: String,
        name: MediaVariantName,
        ciphertext: ByteArray,
    ): String {
        require(localId.matches(Regex("^[A-Za-z0-9-]{1,80}$")))
        val id = "$localId-${name.wireValue}.bin"
        Files.write(file(id), ciphertext)
        return id
    }

    override suspend fun read(blobId: String): ByteArray = Files.readAllBytes(file(blobId))

    override suspend fun delete(blobId: String) {
        val path = file(blobId)
        if (Files.exists(path)) {
            runCatching { Files.write(path, ByteArray(Files.size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) }
            Files.deleteIfExists(path)
        }
    }

    override suspend fun clear() {
        Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                runCatching {
                    Files.write(path, ByteArray(Files.size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun file(id: String): Path {
        require(id.matches(Regex("^[A-Za-z0-9-]{1,100}\\.bin$")))
        return root.resolve(id)
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
