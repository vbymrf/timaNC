package com.tima.client.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object DocumentV2Policy {
    fun validate(
        nodeCount: Int,
        markup: JsonObject?,
        secretMetadata: JsonObject?,
    ) {
        require(markup == null || markup.isNotEmpty()) { "empty markup must be omitted" }
        require(secretMetadata == null || secretMetadata.isNotEmpty()) {
            "empty secret metadata must be omitted"
        }
        val state = State(nodeCount)
        markup?.let { validateElement(it, 1, state) }
        require(nodeCount > 0 || state.hasMedia) { "DocumentV2 has no content" }
        val secretKeys = secretMetadata?.keys.orEmpty()
        require(state.secretRefs == secretKeys) {
            "secret_ref set must exactly match encrypted metadata keys"
        }
    }

    fun canonicalJson(value: JsonElement): ByteArray =
        canonicalText(value).encodeToByteArray()

    private fun validateElement(element: JsonElement, depth: Int, state: State) {
        require(depth <= 10) { "layout depth exceeds 10" }
        when (element) {
            is JsonObject -> validateObject(element, depth, state)
            is JsonArray -> element.forEach { validateElement(it, depth + 1, state) }
            is JsonPrimitive -> {
                if (element.isString) rejectExecutable(element.content)
            }
            JsonNull -> Unit
        }
    }

    private fun validateObject(value: JsonObject, depth: Int, state: State) {
        val type = value["type"]?.let { (it as? JsonPrimitive)?.content }
        require(value.keys.none(::isEventHandler)) { "event handlers are executable content" }
        require("script" !in value && "srcdoc" !in value && "macro" !in value) {
            "executable content is blocked"
        }
        require(value["href"] == null) { "private links must use secret_ref" }
        value["nodes"]?.let { raw ->
            val indexes = raw as? JsonArray ?: error("nodes must be an array")
            require(indexes.isNotEmpty()) { "nodes must not be empty" }
            val seen = mutableSetOf<Int>()
            indexes.forEach {
                val index = (it as? JsonPrimitive)?.intOrNull
                    ?: error("node index must be an integer")
                require(index in 0 until state.nodeCount && seen.add(index)) {
                    "node index is out of bounds or duplicated"
                }
            }
        }
        value["secret_ref"]?.let {
            val ref = (it as? JsonPrimitive)?.content ?: error("secret_ref must be a string")
            require(SECRET_REF.matches(ref) && state.secretRefs.add(ref)) {
                "secret_ref is invalid or duplicated"
            }
        }
        if (type == "media") {
            val id = (value["media_id"] as? JsonPrimitive)?.content
                ?: error("private media requires media_id")
            require(UUID.matches(id)) { "media_id must be a canonical UUID" }
            require(value["secret_ref"] != null) { "private media requires secret_ref" }
            state.hasMedia = true
        }
        if (type == "text_link") {
            require(value["secret_ref"] != null) { "private text_link requires secret_ref" }
        }
        value.values.forEach { validateElement(it, depth + 1, state) }
    }

    private fun rejectExecutable(value: String) {
        val normalized = value.trim().lowercase()
        require(
            !normalized.startsWith("javascript:") &&
                !normalized.startsWith("vbscript:") &&
                !normalized.startsWith("data:text/html") &&
                "<script" !in normalized &&
                "<iframe" !in normalized &&
                "<object" !in normalized &&
                "<embed" !in normalized,
        ) { "executable content is blocked" }
    }

    private fun isEventHandler(key: String): Boolean =
        key.lowercase() in EVENT_HANDLERS

    private fun canonicalText(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { key ->
            JsonPrimitive(key).toString() + ":" + canonicalText(value.getValue(key))
        }
        is JsonArray -> value.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
            transform = ::canonicalText,
        )
        is JsonPrimitive -> value.toString()
        JsonNull -> "null"
    }

    private class State(val nodeCount: Int) {
        var hasMedia: Boolean = false
        val secretRefs = mutableSetOf<String>()
    }

    private val SECRET_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )
    private val EVENT_HANDLERS = setOf(
        "onload", "onerror", "onclick", "ondblclick", "onfocus", "onblur",
        "onchange", "oninput", "onsubmit", "onmouseover", "onmouseenter", "onmouseleave",
    )
}
