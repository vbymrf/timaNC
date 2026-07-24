package com.tima.client.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class MediaQueueState {
    PREPARED,
    INITIALIZED,
    UPLOADING,
    COMPLETED,
    ENQUEUING_MESSAGE,
    SENT_TO_OUTBOX,
    RETRY,
    TERMINAL,
}

@Serializable
data class QueuedMediaVariant(
    val name: MediaVariantName,
    val blobId: String,
    val key: ByteArray,
    val sha256: String,
    val size: Long,
    val width: Int,
    val height: Int,
)

@Serializable
data class MediaQueueRecord(
    val localId: String,
    val chatId: String,
    val secretRef: String,
    val messageBinding: MediaMessageBinding,
    val initIdempotencyKey: String,
    val completeIdempotencyKey: String,
    val variants: List<QueuedMediaVariant>,
    val createdAtEpochMillis: Long,
    val mediaId: String? = null,
    val slots: List<PresignedUploadSlot> = emptyList(),
    val uploadExpiresAtEpochMillis: Long? = null,
    val uploaded: Set<MediaVariantName> = emptySet(),
    val state: MediaQueueState = MediaQueueState.PREPARED,
    val retryCount: Int = 0,
    val errorCode: String? = null,
)

@Serializable
data class MediaMessageBinding(
    val localId: String,
    val reservationIdempotencyKey: String,
    val sendIdempotencyKey: String,
)

/**
 * Implementations persist records encrypted with a session-scoped, platform-protected key.
 * Keys and presigned URLs must never be written as plaintext or logged.
 */
interface MediaQueueStore {
    suspend fun put(record: MediaQueueRecord)
    suspend fun get(localId: String): MediaQueueRecord?
    suspend fun pending(): List<MediaQueueRecord>
    suspend fun delete(localId: String)
    suspend fun clear()
}

/** Stores ciphertext variants only; no selected or normalized plaintext is accepted here. */
interface EncryptedMediaBlobStore {
    suspend fun write(localId: String, name: MediaVariantName, ciphertext: ByteArray): String
    suspend fun read(blobId: String): ByteArray
    suspend fun delete(blobId: String)
    suspend fun clear()
}

fun interface MediaMessageSender {
    /**
     * Idempotently reaches the existing durable message-outbox boundary using exactly these IDs.
     * A matching durable outbox row or SENT cached bubble is success without new reservation/encryption.
     */
    suspend fun ensureMediaEnqueued(
        chatId: String,
        attachment: MediaAttachmentUi,
        binding: MediaMessageBinding,
    )
}

fun interface MediaIdGenerator {
    fun next(): String
}

data class MediaUploadUiState(
    val localId: String? = null,
    val state: MediaQueueState? = null,
    val completedVariants: Int = 0,
    val totalVariants: Int = 3,
    val errorCode: String? = null,
    val retryable: Boolean = false,
)

class PrivateImageUploadCoordinator(
    private val normalizer: PlatformImageNormalizer,
    private val queue: MediaQueueStore,
    private val blobs: EncryptedMediaBlobStore,
    private val transport: MediaTransfer,
    private val sender: MediaMessageSender,
    private val ids: MediaIdGenerator,
    private val nowEpochMillis: () -> Long,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(MediaUploadUiState())
    val state: StateFlow<MediaUploadUiState> = mutableState.asStateFlow()

    suspend fun selectAndSend(chatId: String, input: ByteArray): String {
        val normalized = try {
            ExecutableMagic.rejectInput(input)
            normalizer.normalize(input)
        } finally {
            input.fill(0)
        }
        return selectAndSendNormalized(chatId, normalized)
    }

    suspend fun selectAndSendNormalized(chatId: String, normalized: NormalizedImageSet): String {
        val localId = ids.next()
        val binding = MediaMessageBinding(
            localId = ids.next(),
            reservationIdempotencyKey = ids.next(),
            sendIdempotencyKey = ids.next(),
        )
        val initIdempotencyKey = ids.next()
        val completeIdempotencyKey = ids.next()
        val prepared = MediaAead.prepare(normalized)
        val createdBlobIds = mutableListOf<String>()
        val queuedKeys = mutableListOf<ByteArray>()
        var durable = false
        try {
            val queued = prepared.variants.map { variant ->
                val blobId = blobs.write(localId, variant.name, variant.ciphertext)
                createdBlobIds += blobId
                val key = variant.key.copyOf()
                queuedKeys += key
                QueuedMediaVariant(
                    variant.name,
                    blobId,
                    key,
                    variant.sha256,
                    variant.size,
                    variant.width,
                    variant.height,
                )
            }
            val record = MediaQueueRecord(
                localId = localId,
                chatId = chatId,
                secretRef = "media:$localId",
                messageBinding = binding,
                initIdempotencyKey = initIdempotencyKey,
                completeIdempotencyKey = completeIdempotencyKey,
                variants = queued,
                createdAtEpochMillis = nowEpochMillis(),
            )
            queue.put(record)
            durable = true
        } catch (error: Throwable) {
            createdBlobIds.forEach { runCatching { blobs.delete(it) } }
            queuedKeys.forEach { it.fill(0) }
            throw error
        } finally {
            prepared.variants.forEach {
                it.key.fill(0)
                it.ciphertext.fill(0)
            }
            if (!durable) queuedKeys.forEach { it.fill(0) }
        }
        resume(localId)
        return localId
    }

    suspend fun retry(localId: String) = resume(localId)

    suspend fun resumePending() {
        queue.pending().forEach { resume(it.localId) }
    }

    suspend fun resume(localId: String) = mutex.withLock {
        var record = requireNotNull(queue.get(localId)) { "media queue item not found" }
        try {
            validateRecord(record)
            if (record.mediaId == null) {
                val manifest = record.manifest()
                val initialized = transport.initialize(
                    record.chatId,
                    record.initIdempotencyKey,
                    manifest,
                )
                record = record.copy(
                    mediaId = initialized.mediaId,
                    slots = initialized.uploads,
                    uploadExpiresAtEpochMillis = initialized.expiresAtEpochMillis,
                    state = MediaQueueState.INITIALIZED,
                    errorCode = null,
                )
                queue.put(record)
            }
            require(record.slots.map { it.variant } == MediaVariantName.entries)
            for (slot in record.slots) {
                if (slot.variant in record.uploaded) continue
                record = record.copy(state = MediaQueueState.UPLOADING)
                queue.put(record)
                publish(record)
                val variant = record.variants.single { it.name == slot.variant }
                val ciphertext = blobs.read(variant.blobId)
                try {
                    require(ciphertext.size.toLong() == variant.size)
                    require(Sha256.hex(ciphertext) == variant.sha256)
                    transport.put(slot, ciphertext)
                } finally {
                    ciphertext.fill(0)
                }
                record = record.copy(uploaded = record.uploaded + slot.variant)
                queue.put(record)
                publish(record)
            }
            transport.complete(
                requireNotNull(record.mediaId),
                record.completeIdempotencyKey,
                record.manifest(),
            )
            record = record.copy(state = MediaQueueState.COMPLETED)
            queue.put(record)
            val attachment = record.attachment()
            record = record.copy(state = MediaQueueState.ENQUEUING_MESSAGE)
            queue.put(record)
            sender.ensureMediaEnqueued(record.chatId, attachment, record.messageBinding)
            record = record.copy(state = MediaQueueState.SENT_TO_OUTBOX)
            queue.put(record)
            cleanup(record)
            mutableState.value = MediaUploadUiState(
                localId,
                MediaQueueState.SENT_TO_OUTBOX,
                3,
            )
        } catch (cancelled: CancellationException) {
            record = record.copy(
                state = MediaQueueState.RETRY,
                errorCode = "MEDIA_OPERATION_INTERRUPTED",
            )
            queue.put(record)
            throw cancelled
        } catch (error: Exception) {
            val failure = when (error) {
                is MediaTransferException -> error
                is IllegalArgumentException, is IllegalStateException ->
                    MediaTransferException("MEDIA_QUEUE_INVALID", false, cause = error)
                else -> MediaTransferException("MEDIA_OPERATION_FAILED", true, cause = error)
            }
            if (failure.refreshUpload) {
                record = record.copy(
                    mediaId = null,
                    slots = emptyList(),
                    uploadExpiresAtEpochMillis = null,
                    uploaded = emptySet(),
                    initIdempotencyKey = ids.next(),
                    completeIdempotencyKey = ids.next(),
                )
            }
            record = record.copy(
                state = if (failure.retryable) MediaQueueState.RETRY else MediaQueueState.TERMINAL,
                retryCount = record.retryCount + 1,
                errorCode = failure.code,
            )
            queue.put(record)
            mutableState.value = MediaUploadUiState(
                localId,
                record.state,
                record.uploaded.size,
                errorCode = record.errorCode,
                retryable = failure.retryable,
            )
        }
    }

    suspend fun wipeSession() {
        queue.pending().forEach { it.variants.forEach { variant -> variant.key.fill(0) } }
        queue.clear()
        blobs.clear()
        mutableState.value = MediaUploadUiState()
    }

    private suspend fun cleanup(record: MediaQueueRecord) {
        var failure: Throwable? = null
        record.variants.forEach {
            try {
                blobs.delete(it.blobId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failure = failure ?: error
            }
        }
        if (failure == null) {
            try {
                queue.delete(record.localId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failure = error
            }
        }
        failure?.let {
            throw MediaTransferException("MEDIA_CLEANUP_FAILED", retryable = true, cause = it)
        }
        record.variants.forEach { it.key.fill(0) }
    }

    private fun publish(record: MediaQueueRecord) {
        mutableState.value = MediaUploadUiState(
            record.localId,
            record.state,
            record.uploaded.size,
            errorCode = record.errorCode,
            retryable = record.state == MediaQueueState.RETRY,
        )
    }

    private fun validateRecord(record: MediaQueueRecord) {
        require(record.localId.isNotBlank() && record.chatId.isNotBlank())
        require(record.createdAtEpochMillis > 0)
        require(record.messageBinding.localId.isNotBlank())
        require(record.messageBinding.reservationIdempotencyKey.isNotBlank())
        require(record.messageBinding.sendIdempotencyKey.isNotBlank())
        require(record.variants.map { it.name } == MediaVariantName.entries)
        require(record.variants.map { it.blobId }.distinct().size == 3)
        record.variants.forEach {
            require(it.key.size == 32)
            require(it.size in 1..MEDIA_CIPHERTEXT_LIMIT_BYTES)
            require(it.sha256.matches(Regex("^[0-9a-f]{64}$")))
            require(it.width in 1..it.name.maxEdge && it.height in 1..it.name.maxEdge)
        }
        require(record.uploaded.all { name -> record.slots.any { it.variant == name } })
        if (record.mediaId != null) {
            require(record.slots.map { it.variant } == MediaVariantName.entries)
            val expiry = requireNotNull(record.uploadExpiresAtEpochMillis)
            require(record.slots.all { it.expiresAtEpochMillis <= expiry })
        } else {
            require(
                record.slots.isEmpty() &&
                    record.uploaded.isEmpty() &&
                    record.uploadExpiresAtEpochMillis == null,
            )
        }
    }

    private fun MediaQueueRecord.manifest() = variants.map {
        MediaVariantManifest(it.name, "application/octet-stream", it.size, it.sha256)
    }

    private fun MediaQueueRecord.attachment(): MediaAttachmentUi {
        fun secret(name: MediaVariantName): MediaVariantSecret {
            val value = variants.single { it.name == name }
            return MediaVariantSecret(
                key = Base64Url.encode(value.key),
                sha256 = value.sha256,
                size = value.size,
                width = value.width,
                height = value.height,
            )
        }
        return MediaAttachmentUi(
            mediaId = requireNotNull(mediaId),
            secretRef = secretRef,
            thumbnail = secret(MediaVariantName.THUMBNAIL),
            preview = secret(MediaVariantName.PREVIEW),
            full = secret(MediaVariantName.FULL),
        )
    }
}

class PrivateImageDownloader(private val transport: MediaTransfer) {
    suspend fun download(attachment: MediaAttachmentUi, variant: MediaVariantName): ByteArray {
        require(variant != MediaVariantName.FULL) { "Phase 1 UI downloads thumbnail or preview only" }
        val secret = when (variant) {
            MediaVariantName.THUMBNAIL -> attachment.thumbnail
            MediaVariantName.PREVIEW -> attachment.preview
            MediaVariantName.FULL -> error("unreachable")
        }
        val access = transport.access(attachment.mediaId, variant)
        val ciphertext = transport.download(access)
        val key = Base64Url.decode(secret.key)
        return try {
            MediaAead.decrypt(
                ciphertext,
                key,
                secret.sha256,
                secret.size,
                secret.width,
                secret.height,
                variant.maxEdge,
            )
        } finally {
            key.fill(0)
            ciphertext.fill(0)
        }
    }
}
