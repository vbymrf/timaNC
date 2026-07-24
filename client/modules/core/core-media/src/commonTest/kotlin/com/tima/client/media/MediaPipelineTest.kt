package com.tima.client.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaPipelineTest {
    @Test
    fun aeadRoundTripRejectsWrongKeyAndVersion() {
        val jpeg = jpeg("KNOWN-PRIVATE-PIXELS")
        val encrypted = MediaAead.encrypt(
            NormalizedImageVariant(MediaVariantName.THUMBNAIL, jpeg.copyOf(), 1, 1),
        )
        assertFalse(encrypted.ciphertext.decodeToString().contains("KNOWN-PRIVATE-PIXELS"))

        val decrypted = MediaAead.decrypt(
            encrypted.ciphertext,
            encrypted.key,
            encrypted.sha256,
            encrypted.size,
            1,
            1,
            40,
        )
        assertContentEquals(jpeg, decrypted)
        assertFails {
            MediaAead.decrypt(
                encrypted.ciphertext,
                ByteArray(32) { 7 },
                encrypted.sha256,
                encrypted.size,
                1,
                1,
                40,
            )
        }
        val wrongVersion = encrypted.ciphertext.copyOf().also { it[9] = 2 }
        assertFails {
            MediaAead.decrypt(wrongVersion, encrypted.key, Sha256.hex(wrongVersion), wrongVersion.size.toLong(), 1, 1, 40)
        }
        encrypted.key.fill(0)
        decrypted.fill(0)
    }

    @Test
    fun documentMetadataIsExactAndMalformedValuesFailClosed() {
        val secret = MediaVariantSecret(Base64Url.encode(ByteArray(32) { 1 }), "ab".repeat(32), 100, 10, 10)
        val attachment = MediaAttachmentUi(
            "00000000-0000-4000-8000-000000000001",
            "media:one",
            thumbnail = secret,
            preview = secret.copy(width = 20, height = 20),
            full = secret.copy(width = 30, height = 30),
        )
        val markup = PrivateImageDocument.markup(attachment.mediaId, attachment.secretRef)
        val metadata = PrivateImageDocument.secretMetadata(attachment.secretRef, attachment)
        assertEquals(setOf("entities"), markup.keys)
        assertTrue(PrivateImageDocument.isPrivateImageMarkup(markup))
        assertFalse(PrivateImageDocument.isPrivateImageMarkup(buildJsonObject { put("type", "media") }))
        assertEquals(attachment, PrivateImageDocument.parse(markup, metadata))
        assertFails {
            PrivateImageDocument.parse(markup, JsonObject(metadata + ("unexpected" to metadata)))
        }
    }

    @Test
    fun durableRetryResumesEncryptedVariantsAndEnqueuesMessage() = runBlocking {
        val queue = MemoryQueue()
        val blobs = MemoryBlobs()
        val transfer = FakeTransfer()
        var sent: MediaAttachmentUi? = null
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-4000-8000-000000000010",
                "00000000-0000-4000-8000-000000000011",
                "00000000-0000-4000-8000-000000000012",
                "00000000-0000-4000-8000-000000000013",
                "00000000-0000-4000-8000-000000000014",
                "00000000-0000-4000-8000-000000000015",
            ),
        )
        val normalizer = PlatformImageNormalizer {
            NormalizedImageSet(MediaVariantName.entries.map {
                NormalizedImageVariant(it, jpeg("KNOWN-$it"), 1, 1)
            })
        }
        val first = PrivateImageUploadCoordinator(
            normalizer,
            queue,
            blobs,
            transfer,
            MediaMessageSender { _, attachment, _ -> sent = attachment },
            MediaIdGenerator { ids.removeFirst() },
            nowEpochMillis = { 1_000L },
        )
        transfer.failNextPut = true
        first.selectAndSend("00000000-0000-4000-8000-000000000020", jpeg("source"))
        assertEquals(MediaQueueState.RETRY, queue.values.single().state)
        assertTrue(blobs.values.values.all { !it.decodeToString().contains("KNOWN") })

        val resumed = PrivateImageUploadCoordinator(
            normalizer,
            queue,
            blobs,
            transfer,
            MediaMessageSender { _, attachment, _ -> sent = attachment },
            MediaIdGenerator { error("resume must reuse durable identifiers") },
            nowEpochMillis = { 1_000L },
        )
        resumed.resumePending()

        assertNotNull(sent)
        assertTrue(queue.values.isEmpty())
        assertTrue(blobs.values.isEmpty())
        assertEquals(MediaVariantName.entries, transfer.completed.map { it.name })
        assertEquals(3, transfer.completed.map { it.sha256 }.distinct().size)
    }

    @Test
    fun executableAndOversizedInputsAreRejected() {
        assertFails { ExecutableMagic.rejectInput(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)) }
        assertFails { ExecutableMagic.rejectInput(ByteArray(MEDIA_INPUT_LIMIT_BYTES + 1)) }
    }

    @Test
    fun restCodecRejectsNonReadyCompletionAndParsesUtcStrictly() {
        val manifest = MediaVariantName.entries.map {
            MediaVariantManifest(it, "application/octet-stream", 100, "ab".repeat(32))
        }
        val variantsJson = manifest.joinToString(",") {
            """{"name":"${it.name.wireValue}","content_type":"application/octet-stream","size":100,"sha256":"${it.sha256}"}"""
        }
        val processing = Json.parseToJsonElement(
            """{"id":"00000000-0000-4000-8000-000000000001","kind":"image","content_mode":"private","status":"processing","variants":[$variantsJson],"created_at":"2026-07-24T00:00:00Z"}""",
        ).jsonObject
        assertFails {
            MediaRestCodec.completed(
                processing,
                "00000000-0000-4000-8000-000000000001",
                manifest,
            )
        }
        assertEquals(0L, Rfc3339Utc.parseEpochMillis("1970-01-01T00:00:00Z"))
    }

    @Test
    fun preDurableFailureDeletesBlobsAndWipesEveryBufferAndKey() = runBlocking {
        val queue = MemoryQueue().apply { failPut = true }
        val blobs = MemoryBlobs()
        val normalized = MediaVariantName.entries.map {
            NormalizedImageVariant(it, jpeg("WIPE-$it"), 1, 1)
        }
        val input = jpeg("SOURCE")
        val ids = ArrayDeque((1..6).map { "id-$it" })
        val coordinator = PrivateImageUploadCoordinator(
            PlatformImageNormalizer { NormalizedImageSet(normalized) },
            queue,
            blobs,
            FakeTransfer(),
            MediaMessageSender { _, _, _ -> },
            MediaIdGenerator { ids.removeFirst() },
            nowEpochMillis = { 1_000L },
        )

        assertFails {
            coordinator.selectAndSend("chat", input)
        }

        assertTrue(input.all { it == 0.toByte() })
        assertTrue(normalized.all { variant -> variant.jpeg.all { it == 0.toByte() } })
        assertTrue(blobs.values.isEmpty())
        assertTrue(queue.captured!!.variants.all { variant -> variant.key.all { it == 0.toByte() } })
    }

    private fun jpeg(value: String) = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(),
        0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08,
        0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
    ) + value.encodeToByteArray() + byteArrayOf(0xff.toByte(), 0xd9.toByte())

    private class MemoryQueue : MediaQueueStore {
        val values = mutableListOf<MediaQueueRecord>()
        var failPut = false
        var captured: MediaQueueRecord? = null
        override suspend fun put(record: MediaQueueRecord) {
            captured = record
            if (failPut) error("queue failure")
            values.removeAll { it.localId == record.localId }
            values += record
        }
        override suspend fun get(localId: String) = values.singleOrNull { it.localId == localId }
        override suspend fun pending() = values.toList()
        override suspend fun delete(localId: String) { values.removeAll { it.localId == localId } }
        override suspend fun clear() = values.clear()
    }

    private class MemoryBlobs : EncryptedMediaBlobStore {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun write(localId: String, name: MediaVariantName, ciphertext: ByteArray): String =
            "$localId-${name.wireValue}".also { values[it] = ciphertext.copyOf() }
        override suspend fun read(blobId: String) = values.getValue(blobId).copyOf()
        override suspend fun delete(blobId: String) { values.remove(blobId)?.fill(0) }
        override suspend fun clear() { values.values.forEach { it.fill(0) }; values.clear() }
    }

    private class FakeTransfer : MediaTransfer {
        var failNextPut = false
        var completed = emptyList<MediaVariantManifest>()
        override suspend fun initialize(
            chatId: String,
            idempotencyKey: String,
            manifest: List<MediaVariantManifest>,
        ) = MediaUploadSlots(
            "00000000-0000-4000-8000-000000000030",
            MediaVariantName.entries.map {
                PresignedUploadSlot(it, "https://media.example/${it.wireValue}", emptyMap(), 10_000L)
            },
            10_000L,
        )
        override suspend fun put(slot: PresignedUploadSlot, ciphertext: ByteArray) {
            if (failNextPut) {
                failNextPut = false
                throw MediaTransferException("TRANSIENT", true)
            }
        }
        override suspend fun complete(
            mediaId: String,
            idempotencyKey: String,
            manifest: List<MediaVariantManifest>,
        ) { completed = manifest }
        override suspend fun access(mediaId: String, variant: MediaVariantName) =
            error("not used")
        override suspend fun download(value: MediaAccessUrl): ByteArray = error("not used")
    }
}
