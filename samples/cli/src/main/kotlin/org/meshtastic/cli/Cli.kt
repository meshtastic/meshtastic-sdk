/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import org.meshtastic.cli.cmd.ConformanceCmd
import org.meshtastic.cli.cmd.EventsCmd
import org.meshtastic.cli.cmd.HealthCmd
import org.meshtastic.cli.cmd.InfoCmd
import org.meshtastic.cli.cmd.NodesCmd
import org.meshtastic.cli.cmd.PacketsCmd
import org.meshtastic.cli.cmd.ProbeCmd
import org.meshtastic.cli.cmd.ScanCmd
import org.meshtastic.cli.cmd.SendCmd
import org.meshtastic.cli.cmd.TuiCmd
import org.meshtastic.cli.internal.DEFAULT_CONNECT_TIMEOUT_MS
import org.meshtastic.cli.internal.Output
import org.meshtastic.cli.internal.TransportRef
import org.meshtastic.cli.internal.parseDurationMs

/**
 * `cli` — Meshtastic physical test harness. Clikt owns argv parsing, `--help` generation,
 * and exit codes; subcommands live in [org.meshtastic.cli.cmd].
 *
 * Pass `--json` at root level to switch every subcommand to line-delimited NDJSON envelopes
 * (see [Output] for the contract). Per-subcommand `--timeout`/`--transport` flags are shared
 * via [TransportOptions] so the spelling never drifts across commands.
 */
internal class CliRoot : CliktCommand(name = "cli") {
    init {
        versionOption(CLI_VERSION)
        subcommands(
            ScanCmd(),
            InfoCmd(),
            NodesCmd(),
            PacketsCmd(),
            EventsCmd(),
            HealthCmd(),
            SendCmd(),
            ProbeCmd(),
            TuiCmd(),
            ConformanceCmd(),
        )
    }

    override fun help(context: Context) = """
        Meshtastic physical test harness — drive any transport against any device.

        Pass --json at root level to emit line-delimited JSON envelopes instead of human text.
    """.trimIndent()

    val json by option("--json", help = "Emit NDJSON envelopes on stdout instead of human text.").flag()

    override fun run() {
        currentContext.obj = CliConfig(out = Output(json = json))
    }
}

/** Per-invocation shared state passed down through Clikt's [Context.obj]. */
internal data class CliConfig(val out: Output)

/** Shared base class: exposes [out] and a tiny [exit] helper for non-zero exit codes. */
internal abstract class BaseCommand(name: String) : CliktCommand(name = name) {
    private val config by requireObject<CliConfig>()
    internal val out: Output get() = config.out
    internal fun exit(code: Int): Nothing = throw ProgramResult(code)
}

/**
 * Shared `--transport`/`--timeout` pair mixed into every session-opening subcommand. Single
 * source of truth for the transport-spec flag eliminates the historical drift between
 * `tui`/`probe`/`info` etc.
 */
internal class TransportOptions : OptionGroup(name = "Transport") {
    val transport by option(
        "--transport",
        help = "ble:NEEDLE | tcp:HOST[:PORT] | serial:PORT[:BAUD]",
        metavar = "SPEC",
    ).convert { spec ->
        TransportRef.parse(spec) ?: fail(
            "malformed transport '$spec' (expected ble:NEEDLE | tcp:HOST[:PORT] | serial:PORT[:BAUD])",
        )
    }.required()

    val timeoutMs by option(
        "--timeout",
        help = "Connect timeout as 30s / 5m / 2000ms (default ${DEFAULT_CONNECT_TIMEOUT_MS}ms).",
        metavar = "DURATION",
    ).durationMs(DEFAULT_CONNECT_TIMEOUT_MS)
}

/** Extension: parse a duration-literal option into ms, with a compile-time default. */
internal fun RawOption.durationMs(default: Long) =
    convert { raw -> parseDurationMs(raw) ?: fail("bad duration '$raw'") }.default(default)

/** Extension: parse a duration-literal option into ms; null when unset. */
internal fun RawOption.optionalDurationMs() = convert { raw -> parseDurationMs(raw) ?: fail("bad duration '$raw'") }

internal const val CLI_VERSION = "0.2.0-dev"

/** Executable entry point — Clikt handles help, unknown flags, usage errors, and exit codes. */
fun main(args: Array<String>) = CliRoot().main(args)
