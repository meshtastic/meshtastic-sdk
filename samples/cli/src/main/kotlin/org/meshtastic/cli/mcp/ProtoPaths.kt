/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import com.squareup.moshi.Moshi
import com.squareup.wire.Message
import com.squareup.wire.WireJsonAdapterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.lang.reflect.Modifier

/**
 * Proto messages as JSON, and dotted-path edits applied to them. Paths name generated proto
 * fields (`hop_limit`, `map_report_settings.publish_interval_secs`); a path that names no field is
 * a tool error rather than a silently dropped key.
 */
internal object ProtoPaths {
    // Identity values are written so a reader always sees every field, not only the non-defaults.
    private val moshi: Moshi = Moshi.Builder().add(WireJsonAdapterFactory(writeIdentityValues = true)).build()

    fun toJson(message: Message<*, *>): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter(message.javaClass as Class<Message<*, *>>)
        // Wire writes camelCase json names; callers see the proto field names.
        return snakeKeys(Json.parseToJsonElement(adapter.toJson(message))) as JsonObject
    }

    private fun snakeKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (k, v) -> snake(k) to snakeKeys(v) })
        is JsonArray -> JsonArray(element.map { snakeKeys(it) })
        else -> element
    }

    /** Decodes [json] into [type]; a key naming no field, at any depth, is a tool error (Moshi would drop it). */
    fun <M : Message<*, *>> fromJson(type: Class<M>, json: JsonObject): M {
        rejectUnknownKeys(type, json, type.simpleName)
        return try {
            moshi.adapter(type).fromJson(json.toString()) ?: toolError("empty ${type.simpleName}")
        } catch (e: com.squareup.moshi.JsonDataException) {
            toolError("bad value for ${type.simpleName}: ${e.message}", e)
        }
    }

    private fun rejectUnknownKeys(type: Class<*>, json: JsonObject, where: String) {
        for ((key, value) in json) {
            val name = snake(key)
            val field = type.fields.firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }
                ?: toolError("unknown field '$key' in $where (${type.simpleName})")
            val nested = messageType(field.genericType) ?: continue
            when (value) {
                is JsonObject -> rejectUnknownKeys(nested, value, "$where.$key")

                is JsonArray -> value.forEachIndexed { i, item ->
                    if (item is JsonObject) rejectUnknownKeys(nested, item, "$where.$key[$i]")
                }

                else -> Unit
            }
        }
    }

    /** The message class of a message-typed or list-of-message field, else null. */
    private fun messageType(generic: java.lang.reflect.Type): Class<*>? = when (generic) {
        is Class<*> -> generic.takeIf { Message::class.java.isAssignableFrom(it) }

        is java.lang.reflect.ParameterizedType -> (generic.actualTypeArguments.singleOrNull() as? Class<*>)
            ?.takeIf { Message::class.java.isAssignableFrom(it) }

        else -> null
    }

    /** Leaf paths of [edits], with nested objects flattened into dotted keys. */
    fun flatten(edits: JsonObject, prefix: String = ""): Map<String, JsonElement> = buildMap {
        for ((key, value) in edits) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject && value.isNotEmpty()) putAll(flatten(value, path)) else put(path, value)
        }
    }

    /** [base] with each dotted path in [edits] (relative to [type]) set to its value. */
    fun <M : Message<*, *>> merge(type: Class<M>, base: M, edits: Map<String, JsonElement>): M {
        var tree = toJson(base)
        for ((path, value) in edits) {
            tree = set(tree, canonical(type, path), value)
        }
        return restoreUnknownFields(base, fromJson(type, tree))
    }

    /** JSON drops fields this build does not know; copies them from [base] onto [next], recursing into messages. */
    @Suppress("UNCHECKED_CAST")
    private fun <M : Message<*, *>> restoreUnknownFields(base: M, next: M): M {
        val builder = next.newBuilder()
        builder.addUnknownFields(base.unknownFields)
        for (field in next.javaClass.fields.filter { !Modifier.isStatic(it.modifiers) }) {
            val old = field.get(base) as? Message<*, *>
            val new = field.get(next) as? Message<*, *>
            if (old != null && new != null) {
                builder.javaClass.getField(field.name).set(builder, restoreUnknownFields(old, new))
            }
        }
        return builder.build() as M
    }

    /** [merge] for a message whose class is only known at runtime. */
    fun mergeAny(base: Message<*, *>, edits: Map<String, JsonElement>): Message<*, *> {
        @Suppress("UNCHECKED_CAST")
        val type = base.javaClass as Class<Message<*, *>>
        return merge(type, base, edits)
    }

    /** The field names [path] resolves to under [type], or a tool error naming the first unknown segment. */
    fun canonical(type: Class<*>, path: String): List<String> {
        var current: Class<*> = type
        val segments = path.split('.')
        return segments.mapIndexed { i, raw ->
            val name = snake(raw)
            val field = current.fields.firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }
                ?: toolError("unknown field '$raw' in '$path' (${current.simpleName})")
            if (i < segments.lastIndex) {
                if (!Message::class.java.isAssignableFrom(field.type)) {
                    toolError("'$raw' in '$path' is not a message, so it has no sub-fields")
                }
                current = field.type
            }
            name
        }
    }

    private fun set(obj: JsonObject, names: List<String>, value: JsonElement): JsonObject {
        val head = names.first()
        val entries = obj.toMutableMap()
        entries.remove(camel(head))
        entries[head] = if (names.size == 1) {
            value
        } else {
            set((entries[head] as? JsonObject) ?: JsonObject(emptyMap()), names.drop(1), value)
        }
        if (names.size == 1 && value == JsonNull) entries.remove(head)
        return JsonObject(entries)
    }

    private fun snake(name: String): String = buildString {
        for (c in name) if (c.isUpperCase()) append('_').append(c.lowercaseChar()) else append(c)
    }

    private fun camel(name: String): String = buildString {
        var up = false
        for (c in name) {
            if (c == '_') up = true else append(if (up) c.uppercaseChar() else c).also { up = false }
        }
    }
}
