package com.tima.client.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DomainApiTest {
    @Test
    fun optionalEmptyValuesNormalizeToAbsence() {
        val document = PublicDocumentV2.create(
            nodes = emptyList(),
            markup = buildJsonObject { put("entity", "text") },
            metadata = ContentMetadata(1uL, ContentMode.PUBLIC),
        )
        assertNull(document.nodes)
    }

    @Test
    fun publicMediaRequiresExactlyThreeDerivedVariants() {
        val variants = listOf(
            variant(MediaVariantName.THUMBNAIL),
            variant(MediaVariantName.PREVIEW),
            variant(MediaVariantName.FULL),
        )
        MediaAsset.create("media", MediaVisibility.PUBLIC, MediaStatus.READY, variants)
        assertFailsWith<IllegalArgumentException> {
            MediaAsset.create("media", MediaVisibility.PUBLIC, MediaStatus.READY, variants.dropLast(1))
        }
    }

    @Test
    fun immutableRevisionChainRejectsMissingParent() {
        val document = PublicDocumentV2.create(
            nodes = listOf("edit"),
            metadata = ContentMetadata(2uL, ContentMode.PUBLIC),
        )
        assertFailsWith<IllegalArgumentException> {
            MessageRevision("revision", null, 2uL, document, 1)
        }
    }

    @Test
    fun executableMarkupProducesTypedError() {
        assertFailsWith<TimaSdkException.ExecutableContentBlocked> {
            PublicDocumentV2.create(
                nodes = listOf("click"),
                markup = JsonObject(mapOf("href" to JsonPrimitive("javascript:alert(1)"))),
                metadata = ContentMetadata(1uL, ContentMode.PUBLIC),
            )
        }
    }

    private fun variant(name: MediaVariantName) =
        MediaVariant(name, 1uL, "0".repeat(64))
}
