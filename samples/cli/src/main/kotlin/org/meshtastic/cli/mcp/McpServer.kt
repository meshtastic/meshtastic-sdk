/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.meshtastic.cli.CLI_VERSION
import org.meshtastic.cli.internal.DEFAULT_CONNECT_TIMEOUT_MS
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TransportTarget
import java.io.InputStream
import java.io.OutputStream

/**
 * Serves MCP over stdio on [input]/[output] until the client closes stdin, holding one radio
 * session across tool calls. Nothing else may write to [output].
 */
internal class McpServer(private val input: InputStream, private val output: OutputStream) {

    suspend fun serve() = coroutineScope {
        val radio = RadioHolder(this)
        val server = Server(
            serverInfo = Implementation(name = "meshtastic-sdk-cli", version = CLI_VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
            instructions = INSTRUCTIONS,
        )
        registerTools(server, radio)
        val transport = StdioServerTransport(input = input.asSource().buffered(), output = output.asSink().buffered())
        val session = server.createSession(transport)
        val closed = CompletableDeferred<Unit>()
        session.onClose { closed.complete(Unit) }
        // A client that closed before the observer registered never fires it.
        if (session.transport == null) closed.complete(Unit)
        System.err.println("mcp: serving on stdio")
        closed.await()
        System.err.println("mcp: client closed stdin; disconnecting radio")
        radio.close()
        server.close()
    }

    private fun registerTools(server: Server, radio: RadioHolder) {
        server.addTool(
            name = "connect",
            description = "Open the radio session that later calls use, replacing any current one. " +
                "transport is ble:NEEDLE (name, address or substring), tcp:HOST[:PORT] or serial:PORT[:BAUD]. " +
                "Returns `{transport, display, own, node_count, connect_ms}`.",
            inputSchema = schema(required = listOf("transport")) {
                prop("transport", "string", "ble:NEEDLE | tcp:HOST[:PORT] | serial:PORT[:BAUD]")
                prop("timeout_ms", "integer", "Handshake timeout (default $DEFAULT_CONNECT_TIMEOUT_MS).")
                prop("scan_seconds", "integer", "BLE scan window before giving up (default 8).")
            },
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true),
        ) { request -> guarded { connect(radio, request.args()) } }

        server.addTool(
            name = "connection_state",
            description = "The current session's state. Returns `{state, transport, display, own, node_count, " +
                "connected_for_s}`, or `{state: \"NoSession\"}` before connect.",
            inputSchema = schema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { _ -> guarded { connectionState(radio) } }

        for (def in deviceTools) {
            server.addTool(
                name = def.name,
                description = def.description,
                inputSchema = def.schema,
                toolAnnotations = def.annotations,
            ) { request ->
                guarded {
                    val live = radio.live ?: toolError(NOT_CONNECTED)
                    def.handler(live.client, request.args())
                }
            }
        }
    }

    private suspend fun connect(radio: RadioHolder, args: Args): JsonObject {
        val spec = args.string("transport") ?: toolError("transport is required")
        val parsed = TransportTarget.parse(spec)
            ?: toolError("malformed transport '$spec' (expected ble:NEEDLE | tcp:HOST[:PORT] | serial:PORT[:BAUD])")
        val scanSeconds = args.int("scan_seconds")
        val ref = when {
            parsed is TransportTarget.Ble && scanSeconds != null -> parsed.copy(scanSeconds = scanSeconds)
            else -> parsed
        }
        val live = radio.connect(ref, args.long("timeout_ms") ?: DEFAULT_CONNECT_TIMEOUT_MS)
        return buildJsonObject {
            put("transport", live.ref.label)
            put("display", live.displayName)
            live.client.ownNode.value?.let { own -> putJsonObject("own") { nodeFields(own) } }
            put("node_count", live.client.nodeSnapshot().size)
            put("connect_ms", live.connectMs)
        }
    }

    private suspend fun connectionState(radio: RadioHolder): JsonObject {
        val live = radio.live ?: return buildJsonObject { put("state", "NoSession") }
        val state = live.client.connection.value
        return buildJsonObject {
            put("state", describe(state))
            put("transport", live.ref.label)
            put("display", live.displayName)
            live.client.ownNode.value?.let { own -> put("own", NodeId(own.num).toString()) }
            if (state is ConnectionState.Connected) put("node_count", live.client.nodeSnapshot().size)
            put("connected_for_s", (System.currentTimeMillis() - live.connectedAtMs) / MS_PER_S)
        }
    }

    private fun CallToolRequest.args() = Args(arguments ?: JsonObject(emptyMap()))

    private suspend fun guarded(block: suspend () -> JsonObject): CallToolResult = try {
        CallToolResult(content = listOf(TextContent(block().toString())))
    } catch (e: ToolFailure) {
        CallToolResult(content = listOf(TextContent(e.message ?: "failed")), isError = true)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        System.err.println("mcp: tool failed: $e")
        CallToolResult(content = listOf(TextContent("${e::class.simpleName}: ${e.message}")), isError = true)
    }

    private companion object {
        const val MS_PER_S = 1_000L
        const val NANOS_PER_MS = 1_000_000L
        const val NOT_CONNECTED = "no radio session; call connect first"
        const val INSTRUCTIONS = "Drives one Meshtastic radio through the Kotlin SDK over any transport. " +
            "Call connect once; every other tool reuses that session until connect is called again. " +
            "Pass dest to the config and admin tools to reach a remote node over remote admin."
    }
}

internal fun JsonObjectBuilder.nodeFields(node: NodeInfo) {
    put("id", NodeId(node.num).toString())
    node.user?.let { user ->
        if (user.long_name.isNotEmpty()) put("long_name", user.long_name)
        if (user.short_name.isNotEmpty()) put("short_name", user.short_name)
        put("hw_model", user.hw_model.name)
    }
    if (node.last_heard != 0) put("last_heard", node.last_heard.toUInt().toLong())
    if (node.snr != 0f) put("snr", node.snr)
    node.hops_away?.let { put("hops_away", it) }
    if (node.via_mqtt) put("via_mqtt", true)
}

private fun describe(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Connecting -> "Connecting(attempt=${state.attempt})"
    is ConnectionState.Configuring -> "Configuring(${state.phase}, ${state.progress})"
    is ConnectionState.Reconnecting -> "Reconnecting(attempt=${state.attempt}, cause=${state.cause.message})"
}
