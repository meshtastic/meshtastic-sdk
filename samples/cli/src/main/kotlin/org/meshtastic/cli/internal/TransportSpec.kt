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
import org.meshtastic.sdk.TransportTarget
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.ble.bleAdvertisementMatches
import org.meshtastic.sdk.transport.serial.JvmSerialPorts
import org.meshtastic.sdk.transport.tcp.TcpTransport

/** Result of [openTransport] — the [RadioTransport] plus the resolved display name (e.g., scanned BLE name). */
internal data class OpenedTransport(val transport: RadioTransport, val displayName: String)

/** Sentinel exception: BLE scan finished without finding a matching advertisement. */
internal class NoDeviceException(message: String) : RuntimeException(message)

/**
 * Materialises a [TransportTarget] into a live [RadioTransport]. Suspends for BLE while scanning.
 *
 * Throws [NoDeviceException] when a scan completes without a match (caller should map to
 * `ExitCodes.NO_DEVICE`).
 */
internal suspend fun openTransport(ref: TransportTarget): OpenedTransport = when (ref) {
    is TransportTarget.Tcp ->
        OpenedTransport(TcpTransport(host = ref.host, port = ref.port), "${ref.host}:${ref.port}")

    is TransportTarget.Serial ->
        OpenedTransport(JvmSerialPorts.open(ref.port, ref.baud), "${ref.port}@${ref.baud}")

    is TransportTarget.Ble -> {
        val scanner = Scanner {
            filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } }
        }
        val ad = withTimeoutOrNull(ref.scanSeconds * 1000L) {
            scanner.advertisements.first { ad ->
                bleAdvertisementMatches(ref.needle, ad.name, ad.peripheralName, ad.identifier.toString())
            }
        } ?: throw NoDeviceException(
            "no Meshtastic advertisement matched '${ref.needle}' within ${ref.scanSeconds}s",
        )
        val identifier = ad.identifier.toString()
        val displayName = ad.name ?: ad.peripheralName ?: identifier
        OpenedTransport(BleTransport(peripheral = Peripheral(ad), address = identifier), displayName)
    }
}
