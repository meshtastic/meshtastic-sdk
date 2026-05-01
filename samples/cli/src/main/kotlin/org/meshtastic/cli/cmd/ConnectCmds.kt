/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.cmd

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.cli.BaseCommand
import org.meshtastic.cli.TransportOptions
import org.meshtastic.cli.durationMs
import org.meshtastic.cli.internal.ExitCodes
import org.meshtastic.cli.internal.ProtoJson
import org.meshtastic.cli.internal.SessionHandle
import org.meshtastic.cli.internal.SessionResult
import org.meshtastic.cli.internal.connect
import org.meshtastic.cli.optionalDurationMs
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SendOutcome

/**
 * Helper: connect with [tx], run [block] with the live session, always close.
 * On connect-failure emits error/done envelopes and exits with the matching code.
 * On block-failure emits an error in the [context] namespace and exits [ExitCodes.FAILURE].
 */
private suspend inline fun BaseCommand.withSession(
    tx: TransportOptions,
    context: String,
    block: (SessionHandle) -> Unit,
) {
    when (val result = connect(tx.transport, tx.timeoutMs, out)) {
        is SessionResult.Failed -> exit(result.exitCode)

        is SessionResult.Ready -> try {
            block(result.session)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            out.error(context, "${e::class.simpleName}: ${e.message}")
            out.done(context, ExitCodes.FAILURE)
            exit(ExitCodes.FAILURE)
        } finally {
            result.session.close()
        }
    }
}

// ---- Commands that open a session ----------------------------------------------------------------

/** `cli info --transport=…` — one-shot: own node + node count, then exit. */
internal class InfoCmd : BaseCommand(name = "info") {
    private val tx by TransportOptions()

    override fun help(context: Context) = "One-shot snapshot: own node + node count."

    override fun run() = runBlocking {
        withSession(tx, "info") { session ->
            val nodes = session.client.nodeSnapshot()
            val own = session.client.ownNode.value
            out.human("Connected to ${session.displayName}; ${nodes.size} nodes in db.")
            own?.let {
                out.human(
                    "Own node: 0x" + it.num.toUInt().toString(16).padStart(8, '0') + " ${it.user?.long_name ?: ""}",
                )
            }
            out.emit("info") {
                put("transport", session.displayName)
                put("nodeCount", nodes.size)
                putRaw("ownNode", own?.let { ProtoJson.toJson(it) } ?: "null")
            }
            out.done("ok", ExitCodes.OK)
        }
    }
}

/** `cli nodes --transport=… [--watch] [--stream-timeout 30s]` — snapshot or stream the node DB. */
internal class NodesCmd : BaseCommand(name = "nodes") {
    private val tx by TransportOptions()
    private val watch by option("--watch", help = "Stream node changes until timeout or disconnect.").flag()
    private val streamTimeoutMs by option(
        "--stream-timeout",
        help = "Watch-mode timeout (default: infinite with --watch).",
        metavar = "DURATION",
    ).optionalDurationMs()

    override fun help(context: Context) = "Snapshot or stream the node table."

    override fun run() = runBlocking {
        withSession(tx, "nodes") { session ->
            if (!watch) {
                session.client.nodeSnapshot().values.sortedByDescending { it.last_heard }.forEach { node ->
                    out.human("  • ${formatNode(node)}")
                    out.emitRaw("node", nodeData("snapshot", node))
                }
                out.done("ok", ExitCodes.OK)
                return@withSession
            }
            val timeout = streamTimeoutMs ?: Long.MAX_VALUE
            val reason = withTimeoutOrNull(timeout) {
                session.client.nodes.collect { change ->
                    when (change) {
                        is NodeChange.Snapshot -> change.nodes.values.forEach { node ->
                            out.human("snap • ${formatNode(node)}")
                            out.emitRaw("node", nodeData("snapshot", node))
                        }

                        is NodeChange.Added -> {
                            out.human("+ ${formatNode(change.node)}")
                            out.emitRaw("node", nodeData("added", change.node))
                        }

                        is NodeChange.Updated -> {
                            out.human("~ ${formatNode(change.node)}")
                            out.emitRaw("node", nodeData("updated", change.node))
                        }

                        is NodeChange.Removed -> {
                            out.human("- 0x" + change.nodeId.raw.toUInt().toString(16))
                            out.emit("node") {
                                put("op", "removed")
                                put("num", change.nodeId.raw)
                            }
                        }
                    }
                }
                "disconnect"
            } ?: "timeout"
            out.done(reason, ExitCodes.OK)
        }
    }

    private fun formatNode(node: NodeInfo): String {
        val id = "0x" + node.num.toUInt().toString(16).padStart(8, '0')
        val name = node.user?.long_name?.takeIf { it.isNotBlank() } ?: "<unknown>"
        val snr = if (node.snr != 0f) " snr=%.1f".format(node.snr) else ""
        return "$id $name$snr"
    }

    private fun nodeData(op: String, node: NodeInfo): String = "{\"op\":\"$op\",\"node\":${ProtoJson.toJson(node)}}"
}

/** `cli packets --transport=… [--watch] [--filter …] [--stream-timeout 30s]` — stream packets. */
internal class PacketsCmd : BaseCommand(name = "packets") {
    private val tx by TransportOptions()
    private val watch by option("--watch", help = "Stream until timeout or disconnect.").flag()
    private val streamTimeoutMs by option(
        "--stream-timeout",
        help = "Stream timeout (default ${DEFAULT_STREAM_TIMEOUT_MS}ms; infinite with --watch and no value).",
        metavar = "DURATION",
    ).optionalDurationMs()
    private val filter by option(
        "--filter",
        help = "Comma-separated key=value clauses (portnum=TEXT_MESSAGE_APP, from=0xHEX).",
        metavar = "CLAUSES",
    )

    override fun help(context: Context) = "Stream MeshPackets (proto-JSON payloads under --json)."

    override fun run() = runBlocking {
        withSession(tx, "packets") { session ->
            val f = PacketFilter.parse(filter)
            val timeout = streamTimeoutMs ?: if (watch) Long.MAX_VALUE else DEFAULT_STREAM_TIMEOUT_MS
            val reason = withTimeoutOrNull(timeout) {
                session.client.packets.collect { p ->
                    if (!f.matches(p)) return@collect
                    val from = "0x" + p.from.toUInt().toString(16)
                    val to = "0x" + p.to.toUInt().toString(16)
                    val text = p.decoded?.payload?.utf8() ?: "<binary>"
                    val portnum = p.decoded?.portnum?.name ?: "?"
                    out.human("← $from→$to [$portnum] $text")
                    out.emitRaw("packet", ProtoJson.toJson(p))
                }
                "disconnect"
            } ?: "timeout"
            out.done(reason, ExitCodes.OK)
        }
    }
}

private data class PacketFilter(val portnum: String?, val fromNum: Int?) {
    fun matches(p: MeshPacket): Boolean {
        if (portnum != null && p.decoded?.portnum?.name != portnum) return false
        if (fromNum != null && p.from != fromNum) return false
        return true
    }

    companion object {
        fun parse(raw: String?): PacketFilter {
            if (raw.isNullOrBlank()) return PacketFilter(null, null)
            var portnum: String? = null
            var fromNum: Int? = null
            raw.split(",").forEach { clause ->
                val eq = clause.indexOf('=')
                if (eq < 0) return@forEach
                val k = clause.substring(0, eq).trim()
                val v = clause.substring(eq + 1).trim()
                when (k) {
                    "portnum" -> portnum = v
                    "from" -> fromNum = if (v.startsWith("0x")) v.substring(2).toIntOrNull(16) else v.toIntOrNull()
                }
            }
            return PacketFilter(portnum, fromNum)
        }
    }
}

/** `cli events --transport=… [--watch] [--stream-timeout 30s]` — stream high-level MeshEvents. */
internal class EventsCmd : BaseCommand(name = "events") {
    private val tx by TransportOptions()
    private val watch by option("--watch", help = "Stream until timeout or disconnect.").flag()
    private val streamTimeoutMs by option(
        "--stream-timeout",
        help = "Stream timeout (default ${DEFAULT_STREAM_TIMEOUT_MS}ms; infinite with --watch and no value).",
        metavar = "DURATION",
    ).optionalDurationMs()

    override fun help(context: Context) = "Stream MeshEvents."

    override fun run() = runBlocking {
        withSession(tx, "events") { session ->
            val timeout = streamTimeoutMs ?: if (watch) Long.MAX_VALUE else DEFAULT_STREAM_TIMEOUT_MS
            val reason = withTimeoutOrNull(timeout) {
                session.client.events.collect { ev ->
                    out.human("• ${ev::class.simpleName}: $ev")
                    emitEvent(ev)
                }
                "disconnect"
            } ?: "timeout"
            out.done(reason, ExitCodes.OK)
        }
    }

    private fun emitEvent(ev: MeshEvent) = when (ev) {
        is MeshEvent.QueueStatusChanged -> out.emitRaw(
            "event",
            "{\"kind\":\"QueueStatusChanged\",\"queueStatus\":${ProtoJson.toJson(ev.status)}}",
        )

        is MeshEvent.Notification -> out.emitRaw(
            "event",
            "{\"kind\":\"Notification\",\"notification\":${ProtoJson.toJson(ev.notification)}}",
        )

        is MeshEvent.PacketsDropped -> out.emit("event") {
            put("kind", "PacketsDropped")
            put("flow", ev.flow.name)
            put("count", ev.count)
        }

        is MeshEvent.ProtocolWarning -> out.emit("event") {
            put("kind", "ProtocolWarning")
            put("message", ev.message)
        }

        is MeshEvent.TransportError -> out.emit("event") {
            put("kind", "TransportError")
            put("error", ev.error.message ?: ev.error::class.simpleName ?: "?")
        }

        else -> out.emit("event") {
            put("kind", ev::class.simpleName ?: "?")
            put("detail", ev.toString())
        }
    }
}

/** `cli health --transport=… [--timeout 30s]` — boolean reachable-and-handshook check. */
internal class HealthCmd : BaseCommand(name = "health") {
    private val tx by TransportOptions()

    override fun help(context: Context) = "Exit 0 if handshake completes and ownNode is known."

    override fun run() = runBlocking {
        withSession(tx, "health") { session ->
            val ownOk = session.client.ownNode.value != null
            if (ownOk) {
                out.human("✓ healthy")
                out.emit("info") {
                    put("kind", "health")
                    put("status", "ok")
                }
                out.done("ok", ExitCodes.OK)
            } else {
                out.error("health", "handshake done but ownNode unknown")
                out.done("health", ExitCodes.FAILURE)
                exit(ExitCodes.FAILURE)
            }
        }
    }
}

/** `cli send {text}` dispatcher. Only `text` is implemented today. */
internal class SendCmd : BaseCommand(name = "send") {
    init {
        subcommands(SendText())
    }

    override fun help(context: Context) = "Transmit — only 'text' is supported today."

    override fun run() = Unit
}

/** `cli send text --transport=… -m "…" [--to BROADCAST|0xHEX] [--channel N] [--await 30s]`. */
internal class SendText : BaseCommand(name = "text") {
    private val tx by TransportOptions()
    private val message by option("--message", "-m", help = "Message body.", metavar = "TEXT").required()
    private val to by option("--to", help = "Destination: BROADCAST, decimal, or 0xHEX.", metavar = "DEST")
        .default("BROADCAST")
    private val channel by option("--channel", help = "Channel index (default 0).").int().default(0)
    private val awaitMs by option("--await", help = "Wait for terminal outcome (default 30s).", metavar = "DURATION")
        .durationMs(DEFAULT_AWAIT_MS)

    override fun help(context: Context) = "Send a text message and await Acked/Delivered/Failed."

    override fun run() = runBlocking {
        val dest = parseDest(to) ?: run {
            out.error("usage", "bad --to '$to' (expected BROADCAST | decimal | 0xHEX)")
            out.done("usage", ExitCodes.USAGE)
            exit(ExitCodes.USAGE)
        }
        withSession(tx, "send") { session ->
            val handle = session.client.sendText(
                text = message,
                channel = ChannelIndex(channel),
                to = dest,
            )
            out.human("→ queued message id=${handle.id} to=$to ch=$channel")
            out.emit("info") {
                put("kind", "send-queued")
                put("messageId", handle.id.toString())
                put("to", to)
                put("channel", channel)
            }
            val outcome = withTimeoutOrNull(awaitMs) { handle.await() }
            when (outcome) {
                null -> {
                    out.error("timeout", "send did not reach terminal state within ${awaitMs}ms")
                    out.done("timeout", ExitCodes.TIMEOUT)
                    exit(ExitCodes.TIMEOUT)
                }

                is SendOutcome.Success -> {
                    out.human("✓ delivered")
                    out.emit("info") {
                        put("kind", "send-result")
                        put("outcome", "success")
                    }
                    out.done("ok", ExitCodes.OK)
                }

                is SendOutcome.Failure -> {
                    val reason = outcome.reason::class.simpleName ?: "?"
                    out.error("send-failed", reason)
                    out.emit("info") {
                        put("kind", "send-result")
                        put("outcome", "failure")
                        put("reason", reason)
                    }
                    out.done("send-failed", ExitCodes.FAILURE)
                    exit(ExitCodes.FAILURE)
                }
            }
        }
    }

    private fun parseDest(raw: String): NodeId? {
        if (raw.equals("BROADCAST", ignoreCase = true)) return NodeId.BROADCAST
        val n = if (raw.startsWith("0x", ignoreCase = true)) {
            raw.substring(2).toUIntOrNull(16)?.toInt()
        } else {
            raw.toIntOrNull()
        } ?: return null
        return NodeId(n)
    }
}

private const val DEFAULT_STREAM_TIMEOUT_MS = 30_000L
private const val DEFAULT_AWAIT_MS = 30_000L
