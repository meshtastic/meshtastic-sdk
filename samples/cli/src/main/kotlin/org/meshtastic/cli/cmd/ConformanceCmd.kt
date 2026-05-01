/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.cmd

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import org.meshtastic.cli.BaseCommand
import org.meshtastic.cli.TransportOptions
import org.meshtastic.cli.conformance.ScenarioResult
import org.meshtastic.cli.conformance.Scenarios
import org.meshtastic.cli.conformance.Transcript
import org.meshtastic.cli.internal.ExitCodes
import org.meshtastic.cli.internal.openTransport
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * `cli conformance --transport=… [--peer-node=…] [--output transcript.md] [--scenario cs1,cs3]`
 *
 * One-shot, real-radio acceptance sweep matching the Phase 5 checklist in `docs/SPEC.md` §6 +
 * `docs/manual-tests.md`. Connects, runs each scenario in turn, prints a transcript, and exits
 * non-zero on any FAIL.
 *
 * SKIP is **not** a failure: scenarios skipped because of missing prerequisites (no
 * `--peer-node`) leave the exit code unaffected. A reviewer can still spot the gap in the
 * transcript.
 */
internal class ConformanceCmd : BaseCommand(name = "conformance") {

    private val tx by TransportOptions()

    private val peerNode by option(
        "--peer-node",
        help = "Second-radio NodeId for traceRoute (cs4). Skip cs4 if absent. Format: BROADCAST|decimal|0xHEX|!HEX.",
        metavar = "NODE",
    )

    private val output by option(
        "--output",
        "-o",
        help = "Write the markdown transcript to this path (in addition to stdout).",
        metavar = "PATH",
    )

    private val scenarioFilter by option(
        "--scenario",
        help = "Restrict to a comma-separated list of scenario ids (cs1,cs2,cs3,cs4,cs5,cs6).",
        metavar = "CSV",
    ).split(",")

    private val minNodes by option(
        "--min-nodes",
        help = "cs5 minimum NodeDB size (default 1).",
    ).int().default(1)

    private val candidate by option(
        "--candidate",
        help = "Release-candidate label for the transcript header (e.g. v0.1.0-rc1).",
    ).default("untagged")

    override fun help(context: Context) =
        "Run the Phase 5 acceptance sweep (handshake, send-text, admin, traceRoute, NodeDB sync, reconnect)."

    override fun run() = runBlocking {
        val tester = System.getenv("USER") ?: System.getProperty("user.name") ?: "unknown"
        val hostInfo = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

        val results = mutableListOf<ScenarioResult>()
        val client = openClient()
        val displayLabel = tx.transport.label

        try {
            results += Scenarios.cs1HandshakeUnder30s(client).also { announce(it) }

            // If cs1 fails, the rest cannot run — record SKIPs and bail out.
            if (results.last().status != ScenarioResult.Status.PASS) {
                listOf("cs2", "cs3", "cs4", "cs5", "cs6").forEach { id ->
                    results += Scenarios.skip(id, "skipped due to cs1 failure", "handshake never reached Connected")
                        .also { announce(it) }
                }
            } else {
                runIfRequested("cs2") { Scenarios.cs2SendTextRoundTrip(client) }?.let { results += it.also(::announce) }
                runIfRequested("cs3") { Scenarios.cs3AdminGetOwner(client) }?.let { results += it.also(::announce) }
                runIfRequested("cs5") { Scenarios.cs5LargeMeshSync(client, minNodes = minNodes) }
                    ?.let { results += it.also(::announce) }
                val peer = peerNode?.let(::parseNodeId)
                runIfRequested("cs4") {
                    if (peer == null) {
                        Scenarios.skip("cs4", "traceRoute", "no --peer-node supplied")
                    } else {
                        Scenarios.cs4TraceRoute(client, peer)
                    }
                }?.let { results += it.also(::announce) }
                runIfRequested("cs6") { Scenarios.cs6ReconnectAfterDrop(client) }
                    ?.let { results += it.also(::announce) }
            }
        } finally {
            runCatching { client.disconnect() }
        }

        val transcript = Transcript.render(
            results = results,
            candidate = candidate,
            tester = tester,
            device = "device-$displayLabel",
            host = hostInfo,
        )

        // Always print the transcript on stdout (after the per-scenario lines) so a reviewer
        // can copy it without rerunning. --output writes the same content to a file too.
        out.human("")
        out.human(transcript)
        output?.let { path ->
            val target = Path.of(path)
            target.parent?.let(Files::createDirectories)
            target.writeText(transcript)
            out.human("Transcript written to $path")
        }

        // Emit a JSON envelope for machine consumers — single summary, not per-scenario, so
        // the NDJSON contract stays terse.
        val passed = results.count { it.status == ScenarioResult.Status.PASS }
        val failed = results.count { it.status == ScenarioResult.Status.FAIL }
        val skipped = results.count { it.status == ScenarioResult.Status.SKIP }
        out.emit("info") {
            put("kind", "conformance-summary")
            put("passed", passed)
            put("failed", failed)
            put("skipped", skipped)
            put("total", results.size)
        }

        if (failed == 0) {
            out.done("ok", ExitCodes.OK)
        } else {
            out.done("conformance-failed", ExitCodes.FAILURE)
            exit(ExitCodes.FAILURE)
        }
    }

    private fun runIfRequested(id: String, block: suspend () -> ScenarioResult): ScenarioResult? {
        if (scenarioFilter != null && id !in scenarioFilter!!) return null
        return runBlocking { block() }
    }

    private fun announce(r: ScenarioResult) {
        out.human(Transcript.line(r))
        out.emit("info") {
            put("kind", "conformance-scenario")
            put("id", r.id)
            put("status", r.status.name)
            put("durationMs", r.durationMs)
            put("message", r.message)
        }
    }

    /**
     * Build a fresh client wired to the supplied transport. Conformance owns the lifecycle (it
     * needs to call disconnect+reconnect inside cs6) so it bypasses the standard `connect()`
     * helper and constructs the client directly. Storage is rooted in the system temp directory
     * so sqldelight has a writable path; the directory survives across the run's
     * disconnect+reconnect cycle (cs6) for identity continuity.
     */
    private suspend fun openClient(): RadioClient {
        val baseDir = Files.createTempDirectory("cli-conformance-").toAbsolutePath().toString()
        val opened = openTransport(tx.transport)
        return RadioClient.Builder()
            .transport(opened.transport)
            .storage(SqlDelightStorageProvider(baseDir = baseDir))
            // The conformance harness owns the connect lifecycle and verifies behaviour
            // explicitly; opt out of the auto setTime so it doesn't perturb timing.
            .autoSyncTimeOnConnect(false)
            .build()
    }

    private fun parseNodeId(raw: String): NodeId? {
        if (raw.equals("BROADCAST", ignoreCase = true)) return NodeId.BROADCAST
        val stripped = raw.removePrefix("!")
        val n: Int? = when {
            stripped.startsWith("0x", ignoreCase = true) ->
                stripped.substring(2).toUIntOrNull(16)?.toInt()

            stripped.length == 8 && stripped.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                // Bare hex (e.g. "aabbccdd") — accept for parity with the !HEX form.
                stripped.toUIntOrNull(16)?.toInt()

            else -> stripped.toIntOrNull()
        }
        return n?.let { NodeId(it) }
    }
}
