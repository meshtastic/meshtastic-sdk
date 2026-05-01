/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class) // Kable Scanner.services still requires it

package org.meshtastic.cli.internal

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.serial.JvmSerialPorts
import org.meshtastic.sdk.transport.tcp.TcpTransport

/**
 * Compact, agent-friendly transport descriptor parsed from the `--transport=…` CLI flag.
 *
 * Syntax:
 *  - `ble:NEEDLE`              — substring match against name/peripheralName/identifier; empty needle = first match
 *  - `tcp:HOST[:PORT]`         — port defaults to 4403
 *  - `serial:PORT[:BAUD]`      — baud defaults to 115_200
 *
 * `serial:` accepts either bare names (`cu.usbmodem101`) or absolute paths (`/dev/cu.usbmodem101`);
 * macOS-style bare names are passed through unchanged (jSerialComm resolves them).
 */
internal sealed interface TransportRef {
    /** Human-readable label for logs. Stable enough for the CLI output. */
    val label: String

    data class Ble(val needle: String, val scanSeconds: Int = 8) : TransportRef {
        override val label: String get() = "ble ${needle.ifBlank { "<any>" }}"
    }

    data class Tcp(val host: String, val port: Int = 4403) : TransportRef {
        override val label: String get() = "tcp $host:$port"
    }

    data class Serial(val port: String, val baud: Int = JvmSerialPorts.DEFAULT_BAUD) : TransportRef {
        override val label: String get() = "serial $port@$baud"
    }

    companion object {
        /** Parses a `--transport=…` value. Returns null on syntax error. */
        fun parse(spec: String): TransportRef? {
            val colon = spec.indexOf(':')
            if (colon < 0) return null
            val scheme = spec.substring(0, colon).lowercase()
            val rest = spec.substring(colon + 1)
            return when (scheme) {
                "ble" -> Ble(needle = rest)

                "tcp" -> {
                    val parts = rest.split(":")
                    val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 4403
                    Tcp(host, port)
                }

                "serial" -> {
                    val parts = rest.split(":")
                    val port = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
                    val baud = parts.getOrNull(1)?.toIntOrNull() ?: JvmSerialPorts.DEFAULT_BAUD
                    Serial(port, baud)
                }

                else -> null
            }
        }
    }
}

/** Result of [openTransport] — the [RadioTransport] plus the resolved display name (e.g., scanned BLE name). */
internal data class OpenedTransport(val transport: RadioTransport, val displayName: String)

/** Sentinel exception: BLE scan finished without finding a matching advertisement. */
internal class NoDeviceException(message: String) : RuntimeException(message)

/**
 * Materialises a [TransportRef] into a live [RadioTransport]. Suspends for BLE while scanning.
 *
 * Throws [NoDeviceException] when a scan completes without a match (caller should map to
 * `ExitCodes.NO_DEVICE`).
 */
internal suspend fun openTransport(ref: TransportRef): OpenedTransport = when (ref) {
    is TransportRef.Tcp ->
        OpenedTransport(TcpTransport(host = ref.host, port = ref.port), "${ref.host}:${ref.port}")

    is TransportRef.Serial ->
        OpenedTransport(JvmSerialPorts.open(ref.port, ref.baud), "${ref.port}@${ref.baud}")

    is TransportRef.Ble -> {
        val needle = ref.needle.lowercase()
        val scanner = Scanner {
            filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } }
        }
        val ad = withTimeoutOrNull(ref.scanSeconds * 1000L) {
            scanner.advertisements.first { ad ->
                ref.needle.isBlank() ||
                    ad.name?.lowercase()?.contains(needle) == true ||
                    ad.peripheralName?.lowercase()?.contains(needle) == true ||
                    ad.identifier.toString().lowercase().contains(needle)
            }
        } ?: throw NoDeviceException(
            "no Meshtastic advertisement matched '${ref.needle}' within ${ref.scanSeconds}s",
        )
        val identifier = ad.identifier.toString()
        val displayName = ad.name ?: ad.peripheralName ?: identifier
        OpenedTransport(BleTransport(peripheral = Peripheral(ad), address = identifier), displayName)
    }
}
