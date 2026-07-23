package com.tima.client.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class ContentMode { PRIVATE, PUBLIC }

data class ContentMetadata(
    val revisionNumber: ULong,
    val contentMode: ContentMode,
    val formatVersion: UInt = 2u,
) {
    init {
        require(formatVersion == 2u) { "DocumentV2 format_version must be 2" }
        require(revisionNumber > 0uL) { "revision_number must be positive" }
    }
}

sealed interface DocumentV2 {
    val metadata: ContentMetadata
    val markup: JsonObject?
    val compatibleFields: JsonObject
}

class PublicDocumentV2 private constructor(
    val nodes: List<String>?,
    override val markup: JsonObject?,
    override val metadata: ContentMetadata,
    override val compatibleFields: JsonObject,
) : DocumentV2 {
    companion object {
        fun create(
            nodes: List<String>? = null,
            markup: JsonObject? = null,
            metadata: ContentMetadata,
            compatibleFields: JsonObject = JsonObject(emptyMap()),
        ): PublicDocumentV2 {
            require(metadata.contentMode == ContentMode.PUBLIC) { "public document requires public metadata" }
            val normalizedNodes = nodes?.takeIf { it.isNotEmpty() }
            val normalizedMarkup = markup?.takeIf { it.isNotEmpty() }
            require(normalizedNodes != null || hasMarkupContent(normalizedMarkup)) {
                "DocumentV2 must contain nodes or non-empty markup"
            }
            normalizedNodes?.forEach { require(it.isNotEmpty()) { "public text nodes must be non-empty" } }
            rejectExecutable(normalizedMarkup)
            return PublicDocumentV2(normalizedNodes, normalizedMarkup, metadata, compatibleFields)
        }
    }
}

class PrivateDocumentV2 private constructor(
    val encryptedNodes: List<ByteArray>?,
    override val markup: JsonObject?,
    val encryptedMetadata: ByteArray?,
    override val metadata: ContentMetadata,
    override val compatibleFields: JsonObject,
) : DocumentV2 {
    companion object {
        fun create(
            encryptedNodes: List<ByteArray>? = null,
            markup: JsonObject? = null,
            encryptedMetadata: ByteArray? = null,
            metadata: ContentMetadata,
            compatibleFields: JsonObject = JsonObject(emptyMap()),
        ): PrivateDocumentV2 {
            require(metadata.contentMode == ContentMode.PRIVATE) { "private document requires private metadata" }
            val normalizedNodes = encryptedNodes?.takeIf { it.isNotEmpty() }
            val normalizedMarkup = markup?.takeIf { it.isNotEmpty() }
            val normalizedMetadata = encryptedMetadata?.takeIf { it.isNotEmpty() }
            require(normalizedNodes != null || hasMarkupContent(normalizedMarkup)) {
                "DocumentV2 must contain encrypted nodes or non-empty markup"
            }
            normalizedNodes?.forEach { require(it.isNotEmpty()) { "encrypted text nodes must be non-empty" } }
            rejectExecutable(normalizedMarkup)
            return PrivateDocumentV2(
                normalizedNodes?.map { it.copyOf() },
                normalizedMarkup,
                normalizedMetadata?.copyOf(),
                metadata,
                compatibleFields,
            )
        }
    }
}

data class MessageRevision(
    val id: String,
    val parentRevisionId: String?,
    val number: ULong,
    val document: DocumentV2,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank())
        require(number > 0uL)
        require(document.metadata.revisionNumber == number)
        require((number == 1uL) == (parentRevisionId == null))
    }
}

data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val current: MessageRevision,
    val revisionHistory: List<MessageRevision> = emptyList(),
) {
    init {
        require(id.isNotBlank() && chatId.isNotBlank() && senderId.isNotBlank())
        require(revisionHistory.map { it.id }.distinct().size == revisionHistory.size)
    }
}

enum class MediaVisibility { PRIVATE, PUBLIC }
enum class MediaVariantName { ENCRYPTED, THUMBNAIL, PREVIEW, FULL }
enum class MediaStatus { UPLOADING, PROCESSING, READY, BLOCKED }

data class MediaVariant(
    val name: MediaVariantName,
    val sizeBytes: ULong,
    val sha256: String,
) {
    init {
        require(sizeBytes > 0uL)
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
}

class MediaAsset private constructor(
    val id: String,
    val visibility: MediaVisibility,
    val status: MediaStatus,
    val variants: List<MediaVariant>,
) {
    companion object {
        fun create(
            id: String,
            visibility: MediaVisibility,
            status: MediaStatus,
            variants: List<MediaVariant>,
        ): MediaAsset {
            require(id.isNotBlank())
            val names = variants.map { it.name }.toSet()
            require(names.size == variants.size) { "media variant names must be unique" }
            when (visibility) {
                MediaVisibility.PRIVATE ->
                    require(names == setOf(MediaVariantName.ENCRYPTED)) {
                        "private media must expose only encrypted bytes"
                    }
                MediaVisibility.PUBLIC ->
                    require(names == setOf(
                        MediaVariantName.THUMBNAIL,
                        MediaVariantName.PREVIEW,
                        MediaVariantName.FULL,
                    )) { "public media must expose exactly thumbnail, preview and full" }
            }
            return MediaAsset(id, visibility, status, variants)
        }
    }
}

sealed class TimaSdkException(message: String) : RuntimeException(message) {
    class AuthenticationRequired : TimaSdkException("authentication required or expired")
    class PresignedUrlExpired : TimaSdkException("presigned URL expired")
    class ExecutableContentBlocked : TimaSdkException("executable content blocked")
    class MediaPipelineMismatch : TimaSdkException("media pipeline mismatch")
    class ImmutableRevisionConflict : TimaSdkException("immutable revision conflict")
    class RetentionRestricted : TimaSdkException("operation restricted by retention policy")
}

private fun hasMarkupContent(markup: JsonObject?): Boolean =
    markup?.any { (key, value) -> key != "layout" || !isEmptyJson(value) } == true

private fun isEmptyJson(value: JsonElement): Boolean =
    (value is JsonObject && value.isEmpty())

private fun rejectExecutable(markup: JsonObject?) {
    val text = markup?.toString()?.lowercase() ?: return
    if (
        "javascript:" in text || "data:text/html" in text ||
        Regex("\"on[a-z]+\"\\s*:").containsMatchIn(text)
    ) {
        throw TimaSdkException.ExecutableContentBlocked()
    }
}
