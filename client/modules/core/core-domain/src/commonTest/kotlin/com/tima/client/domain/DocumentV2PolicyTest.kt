package com.tima.client.domain

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DocumentV2PolicyTest {
    @Test
    fun mediaOnlyDocumentRequiresExactlyResolvedSecret() {
        PlainTextDocumentV2(
            markup = buildJsonObject {
                put("entities", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "media")
                        put("media_id", "00000000-0000-0000-0000-000000000001")
                        put("secret_ref", "media.key")
                    })
                })
            },
            secretMetadata = buildJsonObject { put("media.key", "encrypted later") },
            metadata = DocumentMetadata(revisionNumber = 1uL),
        )
    }

    @Test
    fun danglingSecretAndExecutableLinkAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PlainTextDocumentV2(
                textNodes = listOf("link"),
                markup = buildJsonObject {
                    put("entities", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text_link")
                            put("nodes", buildJsonArray { add(JsonPrimitive(0)) })
                            put("secret_ref", "link.target")
                        })
                    })
                },
                secretMetadata = buildJsonObject { put("other.key", "unused") },
                metadata = DocumentMetadata(revisionNumber = 1uL),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlainTextDocumentV2(
                textNodes = listOf("link"),
                markup = buildJsonObject {
                    put("entities", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text_link")
                            put("nodes", buildJsonArray { add(JsonPrimitive(0)) })
                            put("href", "javascript:alert(1)")
                        })
                    })
                },
                metadata = DocumentMetadata(revisionNumber = 1uL),
            )
        }
    }
}
