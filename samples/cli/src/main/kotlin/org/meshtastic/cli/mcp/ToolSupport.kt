/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient

/** A tool body: runs against the live [RadioClient] and returns the JSON the MCP client parses. */
internal typealias ToolHandler = suspend (RadioClient, Args) -> JsonObject

/** One MCP tool: its wire description plus the [handler] that serves it. */
internal class ToolDef(
    val name: String,
    val description: String,
    val schema: ToolSchema,
    val annotations: ToolAnnotations,
    val handler: ToolHandler,
)

/** Typed reads over a tool call's raw arguments; a wrongly typed value is a tool error. */
internal class Args(val json: JsonObject) {
    private fun primitive(key: String): JsonPrimitive? = when (val v = json[key]) {
        null -> null
        is JsonPrimitive -> v.takeUnless { it.contentOrNull == null }
        else -> toolError("$key must be a scalar")
    }

    fun has(key: String) = json[key] != null

    fun string(key: String): String? = primitive(key)?.let {
        if (it.isString) it.content else toolError("$key must be a string")
    }

    fun int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: toolError("$key must be an integer") }

    fun long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: toolError("$key must be an integer") }

    fun double(key: String): Double? = primitive(key)?.let { it.doubleOrNull ?: toolError("$key must be a number") }

    fun bool(key: String): Boolean? = primitive(key)?.let { it.booleanOrNull ?: toolError("$key must be a boolean") }

    fun obj(key: String): JsonObject? = when (val v = json[key]) {
        null -> null
        is JsonObject -> v
        else -> toolError("$key must be an object")
    }

    /** A string array, or a single string treated as a one-element array. */
    fun strings(key: String): List<String>? = when (val v = json[key]) {
        null -> null

        is JsonArray -> v.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toolError("$key must hold strings")
        }

        is JsonPrimitive -> if (v.isString) {
            listOf(
                v.content,
            )
        } else {
            toolError("$key must be a string or an array of strings")
        }

        else -> toolError("$key must be an array of strings")
    }

    fun requireString(key: String): String = string(key) ?: toolError("$key is required")

    fun requireDouble(key: String): Double = double(key) ?: toolError("$key is required")

    fun requireInt(key: String): Int = int(key) ?: toolError("$key is required")

    /** `dest` (or the legacy `to`), parsed as a node; null when absent. */
    fun dest(): NodeId? = (string("dest") ?: string("to"))?.let { raw ->
        parseNodeId(raw) ?: toolError("bad node '$raw' (BROADCAST | !aabbccdd | 0xHEX | decimal)")
    }
}

/** Accepts BROADCAST, `!aabbccdd`, `0xHEX` or a decimal node number. */
internal fun parseNodeId(raw: String): NodeId? {
    if (raw.equals("BROADCAST", ignoreCase = true)) return NodeId.BROADCAST
    val n = when {
        raw.startsWith("!") -> raw.substring(1).toUIntOrNull(HEX)?.toInt()
        raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toUIntOrNull(HEX)?.toInt()
        else -> raw.toUIntOrNull()?.toInt()
    } ?: return null
    return NodeId(n)
}

/** Unwraps a successful [AdminResult]; any other variant fails the tool call naming [what] and the variant. */
internal fun <T> AdminResult<T>.orFail(what: String): T = when (this) {
    is AdminResult.Success -> value
    AdminResult.SessionKeyExpired -> toolError("$what: session-key-expired")
    AdminResult.Unauthorized -> toolError("$what: unauthorized (device refused or the target is managed)")
    AdminResult.Timeout -> toolError("$what: timeout waiting for the device")
    AdminResult.RateLimited -> toolError("$what: rate-limited by the device")
    AdminResult.NodeUnreachable -> toolError("$what: node-unreachable")
    is AdminResult.Failed -> toolError("$what: routing error ${routingError.name}")
}

internal fun nodeLabel(id: Int): String = NodeId(id).toString()

internal class SchemaBuilder {
    val props = mutableMapOf<String, JsonObject>()

    fun prop(name: String, type: String, description: String, enum: List<String>? = null, items: String? = null) {
        props[name] = buildJsonObject {
            put("type", type)
            put("description", description)
            if (enum != null) {
                put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
            }
            if (items != null) {
                put("items", buildJsonObject { put("type", items) })
            }
        }
    }
}

internal fun schema(required: List<String>? = null, block: SchemaBuilder.() -> Unit = {}): ToolSchema {
    val b = SchemaBuilder().apply(block)
    return ToolSchema(properties = JsonObject(b.props), required = required)
}

internal val READ_ONLY = ToolAnnotations(readOnlyHint = true, openWorldHint = false)
internal val RADIO_READ = ToolAnnotations(readOnlyHint = true, openWorldHint = true)
internal val RADIO_WRITE = ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true)
internal val RADIO_DESTRUCTIVE = ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = true)

internal const val HEX = 16
internal const val DEFAULT_RPC_TIMEOUT_MS = 30_000L
