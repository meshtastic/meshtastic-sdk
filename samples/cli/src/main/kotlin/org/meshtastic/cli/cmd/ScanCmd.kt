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
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.cli.BaseCommand
import org.meshtastic.cli.durationMs
import org.meshtastic.cli.internal.BleBonding
import org.meshtastic.cli.internal.ExitCodes
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.serial.JvmSerialPorts
import java.net.InetSocketAddress
import java.net.Socket

/**
 * `cli scan {ble|serial|tcp}` — discover candidate devices for one transport family.
 * Dispatches to a sub-subcommand; Clikt generates help for each level.
 */
internal class ScanCmd : BaseCommand(name = "scan") {
    init {
        subcommands(ScanBle(), ScanSerial(), ScanTcp())
    }

    override fun help(context: Context) = "Discover devices on one transport family (ble, serial, tcp)."

    override fun run() = Unit
}

/** `cli scan ble [--seconds N]` — stream Meshtastic BLE adverts; shows bonding status when detectable. */
internal class ScanBle : BaseCommand(name = "ble") {
    private val seconds by option("--seconds", help = "Scan duration.").int().default(DEFAULT_SCAN_SECONDS)

    override fun help(context: Context) = "Scan BLE for Meshtastic advertisements."

    override fun run() {
        val seen = linkedMapOf<String, Advert>()
        out.human("Scanning BLE for ${seconds}s …")
        val scanner = Scanner {
            filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } }
        }
        runBlocking {
            withTimeoutOrNull(seconds * 1_000L) {
                scanner.advertisements.collect { ad ->
                    val id = ad.identifier.toString()
                    if (id !in seen) {
                        seen[id] = Advert(id, ad.name ?: ad.peripheralName ?: "<unnamed>", ad.rssi)
                    }
                }
            }
        }
        val bonded = BleBonding.addresses()
        seen.values.sortedByDescending { it.rssi }.forEach { ad ->
            val bondStatus = when {
                bonded == null -> "? bonding unknown"
                ad.id.lowercase() in bonded -> "✓ bonded"
                else -> "✗ not bonded"
            }
            out.human("  ${rssiBars(ad.rssi)} ${ad.name} [$bondStatus] rssi=${ad.rssi}dBm id=${ad.id}")
            out.emit("scan-hit") {
                put("transport", "ble")
                put("name", ad.name)
                put("identifier", ad.id)
                put("rssi", ad.rssi)
                putAny("bonded", bonded?.let { ad.id.lowercase() in it })
            }
        }
        if (seen.isEmpty()) out.human("No devices found.")
        val exit = if (seen.isEmpty()) ExitCodes.NO_DEVICE else ExitCodes.OK
        out.done(if (seen.isEmpty()) "no-device" else "ok", exit)
        if (exit != 0) exit(exit)
    }

    private data class Advert(val id: String, val name: String, val rssi: Int)

    private fun rssiBars(rssi: Int): String = when {
        rssi >= -50 -> "●●●●●"
        rssi >= -60 -> "●●●●○"
        rssi >= -70 -> "●●●○○"
        rssi >= -80 -> "●●○○○"
        rssi >= -90 -> "●○○○○"
        else -> "○○○○○"
    }
}

/** `cli scan serial` — list every serial port jSerialComm can see. */
internal class ScanSerial : BaseCommand(name = "serial") {
    override fun help(context: Context) = "List visible USB-serial ports."

    override fun run() {
        val ports = JvmSerialPorts.list()
        if (ports.isEmpty()) {
            out.human("No serial ports detected.")
            out.done("no-device", ExitCodes.NO_DEVICE)
            exit(ExitCodes.NO_DEVICE)
        }
        out.human("Serial ports:")
        ports.forEach { p ->
            val vidPid = if (p.usbVendorId != null && p.usbProductId != null) {
                " usb=%04x:%04x".format(p.usbVendorId, p.usbProductId)
            } else {
                ""
            }
            out.human("  • ${p.name}  ${p.description ?: ""}$vidPid")
            out.emit("scan-hit") {
                put("transport", "serial")
                put("port", p.name)
                put("description", p.description ?: "")
                p.usbVendorId?.let { put("usbVendorId", it) }
                p.usbProductId?.let { put("usbProductId", it) }
            }
        }
        out.done("ok", ExitCodes.OK)
    }
}

/** `cli scan tcp --host H [--port 4403] [--timeout 2s]` — cheap reachability probe. */
internal class ScanTcp : BaseCommand(name = "tcp") {
    private val host by option("--host", help = "Target host.", metavar = "HOST").required()
    private val port by option("--port", help = "TCP port (default $DEFAULT_TCP_PORT).").int().default(DEFAULT_TCP_PORT)
    private val timeoutMs by option("--timeout", help = "Connect timeout (default 2s).", metavar = "DURATION")
        .durationMs(DEFAULT_TCP_TIMEOUT_MS)

    override fun help(context: Context) = "TCP reachability probe (connect-only)."

    override fun run() {
        val ok = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                true
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            false
        }
        if (ok) {
            out.human("✓ $host:$port reachable")
            out.emit("scan-hit") {
                put("transport", "tcp")
                put("host", host)
                put("port", port)
            }
            out.done("ok", ExitCodes.OK)
        } else {
            out.human("✗ $host:$port unreachable (within ${timeoutMs}ms)")
            out.done("no-device", ExitCodes.NO_DEVICE)
            exit(ExitCodes.NO_DEVICE)
        }
    }
}

private const val DEFAULT_SCAN_SECONDS = 8
private const val DEFAULT_TCP_PORT = 4403
private const val DEFAULT_TCP_TIMEOUT_MS = 2_000L
