/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class) // Kable Scanner.services still requires it

package org.meshtastic.cli.cmd

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.cli.BaseCommand
import org.meshtastic.cli.internal.ExitCodes
import org.meshtastic.cli.internal.Output
import org.meshtastic.cli.internal.ProbeTimings
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.serial.JvmSerialPorts
import org.meshtastic.sdk.transport.tcp.TcpTransport
import com.github.ajalt.clikt.parameters.arguments.default as argumentDefault

/**
 * `cli probe {ble|tcp|serial|all} …` — reconnect-loop probes.
 *
 * Every probe subcommand runs the same underlying [runProbeLoop] and emits identical
 * `probe-run` / `probe-summary` envelopes. This replaces the four divergent `main()`s
 * and three copies of the probe loop from the pre-rewrite CLI.
 */
internal class ProbeCmd : BaseCommand(name = "probe") {
    init {
        subcommands(ProbeBle(), ProbeTcp(), ProbeSerial(), ProbeAll())
    }

    override fun help(context: Context) = "Reconnect-loop probes for one or every transport."

    override fun run() = Unit
}

/** `cli probe ble NEEDLE [--runs N] [--scan SECONDS]`. */
internal class ProbeBle : BaseCommand(name = "ble") {
    private val needle by argument(
        help = "Substring match against name / peripheralName / identifier. Empty = first match.",
    ).argumentDefault("")
    private val runs by option("--runs", help = "Number of connect cycles.").int().default(DEFAULT_RUNS)
    private val scanSeconds by option(
        "--scan",
        help = "Scan duration to locate the device (default ${DEFAULT_SCAN_SECONDS}s).",
    ).int().default(DEFAULT_SCAN_SECONDS)

    override fun help(context: Context) = "BLE connect/disconnect reconnect loop. Device must be bonded."

    override fun run() = runBlocking {
        val summary = runBleProbe(needle, runs, scanSeconds, out)
        emitSummary(listOf(summary), out)
        val ok = summary.fails == 0 && summary.passes > 0
        if (!ok) exit(ExitCodes.FAILURE)
    }
}

/** `cli probe tcp HOST[:PORT] [--runs N]`. */
internal class ProbeTcp : BaseCommand(name = "tcp") {
    private val target by argument(
        help = "HOST or HOST:PORT (port defaults to 4403).",
    )
    private val runs by option("--runs", help = "Number of connect cycles.").int().default(DEFAULT_RUNS)

    override fun help(context: Context) = "TCP connect/disconnect reconnect loop."

    override fun run() = runBlocking {
        val (host, port) = parseHostPort(target)
        val summary = runTcpProbe(host, port, runs, out)
        emitSummary(listOf(summary), out)
        if (summary.fails > 0) exit(ExitCodes.FAILURE)
    }
}

/** `cli probe serial PORT[:BAUD] [--runs N]`. */
internal class ProbeSerial : BaseCommand(name = "serial") {
    private val target by argument(
        help = "PORT or PORT:BAUD (baud defaults to ${JvmSerialPorts.DEFAULT_BAUD}). A leading /dev/ is stripped.",
    )
    private val runs by option("--runs", help = "Number of connect cycles.").int().default(DEFAULT_RUNS)

    override fun help(context: Context) = "Serial connect/disconnect reconnect loop."

    override fun run() = runBlocking {
        val (port, baud) = parsePortBaud(target)
        val summary = runSerialProbe(port, baud, runs, out)
        emitSummary(listOf(summary), out)
        if (summary.fails > 0) exit(ExitCodes.FAILURE)
    }
}

/** `cli probe all [--serial PORT[:BAUD]] [--tcp HOST[:PORT]] [--ble NEEDLE] [--runs N] [--scan SECONDS]`. */
internal class ProbeAll : BaseCommand(name = "all") {
    private val serial by option("--serial", help = "Serial target (PORT or PORT:BAUD).", metavar = "TARGET")
    private val tcp by option("--tcp", help = "TCP target (HOST or HOST:PORT).", metavar = "TARGET")
    private val ble by option("--ble", help = "BLE needle (substring; empty = first match).", metavar = "NEEDLE")
    private val runs by option("--runs", help = "Runs per transport.").int().default(DEFAULT_RUNS)
    private val scanSeconds by option(
        "--scan",
        help = "BLE scan duration (default ${DEFAULT_SCAN_SECONDS}s).",
    ).int().default(DEFAULT_SCAN_SECONDS)

    override fun help(context: Context) = "Run serial, tcp, and/or ble probes back-to-back; aggregate summary."

    override fun run() = runBlocking {
        val results = mutableListOf<Summary>()

        serial?.let {
            val (port, baud) = parsePortBaud(it)
            results += runSerialProbe(port, baud, runs, out)
            delay(INTER_TRANSPORT_PAUSE_MS)
        }
        tcp?.let {
            val (host, port) = parseHostPort(it)
            results += runTcpProbe(host, port, runs, out)
            delay(INTER_TRANSPORT_PAUSE_MS)
        }
        ble?.let {
            results += runBleProbe(it, runs, scanSeconds, out)
        }

        if (results.isEmpty()) {
            out.error("usage", "probe all requires at least one of --serial, --tcp, --ble")
            out.done("usage", ExitCodes.USAGE)
            exit(ExitCodes.USAGE)
        }
        emitSummary(results, out)
        val allGreen = results.all { it.fails == 0 && (it.passes > 0 || it.notes.isNotEmpty()) }
        if (!allGreen) exit(ExitCodes.FAILURE)
    }
}

// ---- Shared probe machinery ----------------------------------------------------------------------

private const val DEFAULT_RUNS = 3
private const val DEFAULT_SCAN_SECONDS = 8
private const val CONNECT_TIMEOUT_MS = 45_000L
private const val SNAPSHOT_TIMEOUT_MS = 2_000L
private const val INTER_RUN_PAUSE_MS = 3_000L
private const val INTER_RUN_PAUSE_BLE_MS = 5_000L
private const val INTER_TRANSPORT_PAUSE_MS = 2_000L

internal data class Summary(
    val label: String,
    val passes: Int,
    val fails: Int,
    val elapsedMs: List<Long>,
    val notes: String = "",
)

internal fun parseHostPort(raw: String): Pair<String, Int> {
    val parts = raw.split(":")
    val host = parts[0]
    val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_TCP_PORT
    return host to port
}

internal fun parsePortBaud(raw: String): Pair<String, Int> {
    // Accept /dev/foo and foo equivalently — jSerialComm wants the bare name.
    val normalized = raw.removePrefix("/dev/")
    val parts = normalized.split(":")
    val port = parts[0]
    val baud = parts.getOrNull(1)?.toIntOrNull() ?: JvmSerialPorts.DEFAULT_BAUD
    return port to baud
}

private const val DEFAULT_TCP_PORT = 4403

private suspend fun runSerialProbe(port: String, baud: Int, runs: Int, out: Output): Summary {
    out.human("")
    out.human("─── SERIAL  $port @ ${baud}bps  ×$runs ───")
    val avail = JvmSerialPorts.list()
    if (avail.none { it.name == port }) {
        out.human("serial port '$port' not found; available: ${avail.joinToString { it.name }}")
        return Summary("serial $port", 0, 0, emptyList(), notes = "port not found")
    }
    return runProbeLoop("serial $port", runs, out, INTER_RUN_PAUSE_MS) {
        JvmSerialPorts.open(port, baud)
    }
}

private suspend fun runTcpProbe(host: String, port: Int, runs: Int, out: Output): Summary {
    out.human("")
    out.human("─── TCP  $host:$port  ×$runs ───")
    return runProbeLoop("tcp $host:$port", runs, out, INTER_RUN_PAUSE_MS) {
        TcpTransport(host = host, port = port)
    }
}

private suspend fun runBleProbe(needle: String, runs: Int, scanSeconds: Int, out: Output): Summary {
    out.human("")
    out.human("─── BLE  ${needle.ifBlank { "<any>" }}  ×$runs  (scan ${scanSeconds}s) ───")
    val n = needle.lowercase()
    val scanner = Scanner {
        filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } }
    }
    val match: Advertisement? = withTimeoutOrNull(scanSeconds * 1_000L) {
        scanner.advertisements.first { ad ->
            needle.isBlank() ||
                ad.name?.lowercase()?.contains(n) == true ||
                ad.peripheralName?.lowercase()?.contains(n) == true ||
                ad.identifier.toString().lowercase().contains(n)
        }
    }
    if (match == null) {
        out.human("[ble] no advertisement matched '${needle.ifBlank { "<any>" }}' within ${scanSeconds}s")
        return Summary("ble ${needle.ifBlank { "<any>" }}", 0, 0, emptyList(), notes = "no match")
    }
    val displayName = match.name ?: match.peripheralName ?: match.identifier.toString()
    out.human("Match: $displayName  rssi=${match.rssi}dBm  id=${match.identifier}")
    val identifier = match.identifier.toString()
    return runProbeLoop("ble $displayName", runs, out, INTER_RUN_PAUSE_BLE_MS) {
        BleTransport(peripheral = Peripheral(match), address = identifier)
    }
}

/**
 * One probe loop to rule them all. Builds a fresh transport + client per run, connects with
 * [CONNECT_TIMEOUT_MS], waits for the first node snapshot with [SNAPSHOT_TIMEOUT_MS], records
 * the per-state timing via [ProbeTimings], then disconnects. Emits one `probe-run` envelope
 * per iteration.
 */
private suspend fun runProbeLoop(
    label: String,
    runs: Int,
    out: Output,
    perRunPauseMs: Long,
    factory: () -> RadioTransport,
): Summary {
    var pass = 0
    var fail = 0
    val elapsed = mutableListOf<Long>()
    repeat(runs) { i ->
        val n = i + 1
        val transport = try {
            factory()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            out.human("Run $n: ✗ FAIL (factory): ${e::class.simpleName}: ${e.message}")
            out.emit("probe-run") {
                put("label", label)
                put("run", n)
                put("ok", false)
                put("reason", "factory: ${e::class.simpleName}: ${e.message}")
            }
            fail++
            return@repeat
        }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(SqlDelightStorageProvider(baseDir = ""))
            .build()
        val timings = ProbeTimings(transport.state, client.connection)
        val start = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            try {
                client.connect()
                client.connection.first { it is ConnectionState.Connected }
                "OK"
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                "FAIL: ${e::class.simpleName}: ${e.message}"
            }
        } ?: "TIMEOUT"
        val duration = System.currentTimeMillis() - start
        val nodeCount = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { client.nodes.first() }
            ?.let { (it as? NodeChange.Snapshot)?.nodes?.size } ?: -1

        timings.stop()
        val ok = outcome == "OK"
        if (ok) {
            elapsed += duration
            out.human("Run $n: ✓ CONNECTED in ${duration}ms ($nodeCount nodes)  [${timings.format()}]")
            pass++
        } else {
            out.human("Run $n: ✗ $outcome (after ${duration}ms)  [${timings.format()}]")
            fail++
        }
        out.emit("probe-run") {
            put("label", label)
            put("run", n)
            put("ok", ok)
            put("elapsedMs", duration)
            put("nodeCount", nodeCount)
            put("outcome", outcome)
            putObject("timings") {
                timings.snapshot().forEach { (k, v) -> put(k, v) }
            }
        }
        runCatching { client.disconnect() }
        delay(perRunPauseMs)
    }
    return Summary(label, pass, fail, elapsed)
}

private fun emitSummary(results: List<Summary>, out: Output) {
    out.human("")
    out.human("=== SUMMARY ===")
    out.human("transport                       pass/total   median ms   notes")
    out.human("-".repeat(74))
    results.forEach { r ->
        val total = r.passes + r.fails
        val median = r.elapsedMs.sorted().getOrNull(r.elapsedMs.size / 2) ?: -1L
        out.human("%-31s %4d/%-4d    %9d   %s".format(r.label, r.passes, total, median, r.notes))
        out.emit("probe-summary") {
            put("label", r.label)
            put("passes", r.passes)
            put("fails", r.fails)
            put("total", total)
            put("medianMs", median)
            put("notes", r.notes)
        }
    }
    val allGreen = results.all { it.fails == 0 && (it.passes > 0 || it.notes.isNotEmpty()) }
    out.done(if (allGreen) "ok" else "probe-failed", if (allGreen) ExitCodes.OK else ExitCodes.FAILURE)
}
