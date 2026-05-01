/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

/**
 * Tiny stdout writer for both human and `--json` modes.
 *
 * **Envelope contract** (`--json`):
 *   `{"type":"…","ts":<epoch-ms>,"data":{…}}` — one per line, NDJSON.
 *
 * Stable envelope `type` values used across subcommands:
 *  - `node` — a node update (`packets`/`nodes`/`watch`)
 *  - `packet` — a mesh packet
 *  - `event` — a `MeshEvent`
 *  - `state` — a connection-state transition
 *  - `scan-hit` — a scan result (`scan ble|serial|tcp`)
 *  - `info` — one-shot informational payload (e.g., `info`, `version`, `health`)
 *  - `probe-run` — a single probe iteration result
 *  - `probe-summary` — final summary across all probe runs
 *  - `error` — terminating error (also goes to stderr in human mode)
 *  - `done` — final envelope before exit; `data:{reason,exit}`
 *
 * `error` envelopes always go to stdout so a `--json` consumer can parse them; a
 * human-readable copy is also written to stderr.
 */
internal class Output(val json: Boolean) {

    fun emit(type: String, build: JsonObjectBuilder.() -> Unit) {
        if (!json) return
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"type\":").append(jsonString(type))
        sb.append(",\"ts\":").append(System.currentTimeMillis())
        sb.append(",\"data\":")
        val obj = JsonObjectBuilder().apply(build)
        sb.append(obj.render())
        sb.append('}')
        println(sb)
    }

    /** Convenience for "no-data" envelopes (e.g., `done` with just reason+exit handled inline). */
    fun emit(type: String) = emit(type) {}

    /**
     * Emit an envelope whose `data` slot is a pre-serialized JSON string (e.g., from
     * [ProtoJson.toJson]). The caller is responsible for ensuring [rawDataJson] is well-formed.
     */
    fun emitRaw(type: String, rawDataJson: String) {
        if (!json) return
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"type\":").append(jsonString(type))
        sb.append(",\"ts\":").append(System.currentTimeMillis())
        sb.append(",\"data\":").append(rawDataJson)
        sb.append('}')
        println(sb)
    }

    /** Final envelope; safe to call before exiting the process. */
    fun done(reason: String, exit: Int) {
        emit("done") {
            put("reason", reason)
            put("exit", exit)
        }
    }

    /** Reports an error: stderr in human mode; both stdout envelope + stderr line in `--json` mode. */
    fun error(code: String, message: String, vararg fields: Pair<String, Any?>) {
        if (json) {
            emit("error") {
                put("code", code)
                put("message", message)
                fields.forEach { (k, v) -> putAny(k, v) }
            }
        }
        System.err.println("error [$code]: $message")
    }

    /** Print a human-readable line; suppressed in `--json` mode. */
    fun human(line: String) {
        if (!json) println(line)
    }

    /** Print a human-readable line; suppressed in `--json` mode (stderr variant for diagnostics). */
    fun humanErr(line: String) {
        if (!json) System.err.println(line)
    }

    /** Always go to stderr regardless of mode (truly diagnostic — never confuses parsers). */
    fun diag(line: String) {
        System.err.println(line)
    }
}

/**
 * Minimal JSON object builder. Escapes strings safely; supports nested objects via [putObject].
 *
 * Deliberately no kotlinx.serialization dependency — the envelopes are small, the surface is fixed.
 */
internal class JsonObjectBuilder {
    private val parts = mutableListOf<String>()

    fun put(key: String, value: String?) {
        parts += "${jsonString(key)}:${if (value == null) "null" else jsonString(value)}"
    }

    fun put(key: String, value: Number) {
        parts += "${jsonString(key)}:$value"
    }

    fun put(key: String, value: Boolean) {
        parts += "${jsonString(key)}:$value"
    }

    fun putRaw(key: String, rawJson: String) {
        parts += "${jsonString(key)}:$rawJson"
    }

    fun putObject(key: String, build: JsonObjectBuilder.() -> Unit) {
        val nested = JsonObjectBuilder().apply(build)
        parts += "${jsonString(key)}:${nested.render()}"
    }

    fun putArray(key: String, items: List<String>) {
        val joined = items.joinToString(",") { jsonString(it) }
        parts += "${jsonString(key)}:[$joined]"
    }

    fun putAny(key: String, value: Any?) {
        when (value) {
            null -> put(key, null as String?)
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        }
    }

    fun render(): String = parts.joinToString(prefix = "{", postfix = "}", separator = ",")
}

private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
