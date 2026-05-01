/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.cmd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.meshtastic.cli.BaseCommand
import org.meshtastic.cli.TransportOptions
import org.meshtastic.cli.internal.ExitCodes
import org.meshtastic.cli.internal.ProbeTimings
import org.meshtastic.cli.internal.openTransport
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider

/**
 * `cli tui --transport=… [--message TEXT]` — interactive Mosaic dashboard.
 *
 * Note: running through Gradle's `:run` task will not provide an interactive TTY
 * and may strip ANSI escapes. Use `installDist` and run the produced launcher
 * script for the proper TUI experience.
 */
internal class TuiCmd : BaseCommand(name = "tui") {
    private val tx by TransportOptions()
    private val message by option(
        "--message",
        "-m",
        help = "Optional broadcast text sent once on handshake.",
        metavar = "TEXT",
    )

    override fun help(context: Context) =
        "Interactive Mosaic dashboard: connection state + nodes + activity + probe timings."

    override fun run() {
        // Own the transport lifecycle manually: Session.connect() is for one-shot flows and
        // its `connected` log line clashes with the Mosaic render. Here we open the transport,
        // build the client, and hand both to the Compose tree.
        val opened = try {
            runBlocking { openTransport(tx.transport) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            out.error("transport-open", "${e::class.simpleName}: ${e.message}")
            out.done("transport-open", ExitCodes.NO_DEVICE)
            exit(ExitCodes.NO_DEVICE)
        }
        val client = RadioClient.Builder()
            .transport(opened.transport)
            .storage(SqlDelightStorageProvider(baseDir = ""))
            .build()
        try {
            runMosaicBlocking { Dashboard(client, opened.transport, tx.transport.label, message) }
        } finally {
            runBlocking { runCatching { client.disconnect() } }
        }
    }
}

private const val MAX_LOG_LINES = 12

@Composable
private fun Dashboard(client: RadioClient, transport: RadioTransport, label: String, initialMessage: String?) {
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    val nodes = remember { mutableStateMapOf<NodeId, NodeInfo>() }
    val log = remember { mutableStateListOf<String>() }
    val timings = remember { ProbeTimings(transport.state, client.connection) }
    var timingTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        wireUpDashboard(
            client = client,
            transport = transport,
            label = label,
            initialMessage = initialMessage,
            nodes = nodes,
            log = log,
            onStateTick = { s ->
                if (s != null) connection = s
                timingTick++
            },
        )
    }

    Column(modifier = Modifier.padding(1)) {
        Header(connection, label)
        Row {
            NodesPanel(nodes)
            ActivityPanel(log)
            TimingsPanel(timings, timingTick)
        }
        Text("")
        Text("Press Ctrl+C to quit.")
    }
}

private suspend fun wireUpDashboard(
    client: RadioClient,
    transport: RadioTransport,
    label: String,
    initialMessage: String?,
    nodes: MutableMap<NodeId, NodeInfo>,
    log: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    onStateTick: (ConnectionState?) -> Unit,
) {
    fun appendLog(line: String) {
        log.add(line)
        while (log.size > MAX_LOG_LINES) log.removeAt(0)
    }
    appendLog("Connecting via $label …")
    coroutineScope {
        launch { client.connection.collect { onStateTick(it) } }
        launch { transport.state.collect { onStateTick(null) } }
        launch {
            client.nodes.collect { change ->
                when (change) {
                    is NodeChange.Snapshot -> {
                        nodes.clear()
                        nodes.putAll(change.nodes)
                        appendLog("Snapshot: ${change.nodes.size} nodes")
                    }

                    is NodeChange.Added -> {
                        nodes[NodeId(change.node.num)] = change.node
                        appendLog("+ node ${nodeName(change.node)}")
                    }

                    is NodeChange.Updated -> nodes[NodeId(change.node.num)] = change.node

                    is NodeChange.Removed -> {
                        nodes.remove(change.nodeId)
                        appendLog("- node 0x" + change.nodeId.raw.toUInt().toString(16))
                    }
                }
            }
        }
        launch {
            client.packets.collect { packet ->
                val from = "0x" + packet.from.toUInt().toString(16)
                val text = packet.decoded?.payload?.utf8() ?: "<binary>"
                val portNum = packet.decoded?.portnum
                appendLog("← $from [$portNum] $text")
            }
        }
        try {
            client.connect()
            appendLog("Handshake complete")
            initialMessage?.let { text ->
                appendLog("→ broadcast: $text")
                client.sendText(text, to = NodeId.BROADCAST)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }
}

@Composable
private fun NodesPanel(nodes: Map<NodeId, NodeInfo>) {
    Column(modifier = Modifier.padding(0, 0, 2, 0)) {
        Text("Nodes (${nodes.size})", color = Color.Cyan, textStyle = TextStyle.Bold)
        if (nodes.isEmpty()) {
            Text("  (waiting for snapshot…)")
        } else {
            nodes.values
                .sortedByDescending { it.last_heard }
                .take(10)
                .forEach { node -> Text("  • ${nodeName(node)}") }
        }
    }
}

@Composable
private fun ActivityPanel(log: List<String>) {
    Column(modifier = Modifier.padding(0, 0, 2, 0)) {
        Text("Activity", color = Color.Cyan, textStyle = TextStyle.Bold)
        log.forEach { line -> Text("  $line") }
    }
}

@Composable
private fun TimingsPanel(timings: ProbeTimings, tick: Int) {
    Column {
        @Suppress("UNUSED_EXPRESSION")
        tick
        Text("Probe Timings", color = Color.Cyan, textStyle = TextStyle.Bold)
        val snap = timings.snapshot()
        if (snap.isEmpty()) {
            Text("  (no events yet)")
        } else {
            snap.forEach { (k, v) -> Text("  %-26s %5d ms".format(k, v)) }
        }
    }
}

@Composable
private fun Header(state: ConnectionState, label: String) {
    val (statusLabel, color) = when (state) {
        ConnectionState.Disconnected -> "DISCONNECTED" to Color.Red

        is ConnectionState.Connecting -> "CONNECTING (#${state.attempt})" to Color.Yellow

        is ConnectionState.Configuring ->
            "CONFIGURING ${state.phase} ${(state.progress * 100).toInt()}%" to Color.Yellow

        ConnectionState.Connected -> "CONNECTED" to Color.Green

        is ConnectionState.Reconnecting -> "RECONNECTING" to Color.Yellow
    }
    Column {
        Text("Meshtastic @ $label", color = Color.White, textStyle = TextStyle.Bold)
        Text("[$statusLabel]", color = color, textStyle = TextStyle.Bold)
        Text("")
    }
}

private fun nodeName(node: NodeInfo): String {
    val id = "0x" + node.num.toUInt().toString(16).padStart(8, '0')
    val name = node.user?.long_name?.takeIf { it.isNotBlank() } ?: "<unknown>"
    val snr = node.snr.takeIf { it != 0f }?.let { " snr=%.1f".format(it) } ?: ""
    return "$id $name$snr"
}
