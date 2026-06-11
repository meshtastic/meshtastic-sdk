/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.toIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ATT MTU requested after each link establishment. ToRadio/FromRadio frames are up to 512 bytes
 * and Android keeps the ATT MTU at the BLE minimum (23 — i.e. 20-byte writes) unless the central
 * explicitly negotiates; 517 is the Android maximum and what Meshtastic firmware expects.
 */
private const val PREFERRED_MTU: Int = 517

/**
 * How long to hold [AndroidPeripheral.Priority.High] after connecting before downgrading to
 * [AndroidPeripheral.Priority.Balanced]. Covers the config handshake window (faster connection
 * interval ≈ faster handshake) without holding the radio at high duty cycle forever.
 */
private const val CONNECTION_PRIORITY_BOOST_MS: Long = 30_000L

/**
 * Android-specific factory that creates a [BleTransport] from a persisted MAC address string.
 *
 * On Android, Kable's `Identifier` is a type alias for `String`, but [toIdentifier] is the
 * canonical conversion and remains correct across Kable versions.
 *
 * After each successful link establishment the transport negotiates the ATT MTU
 * ([PREFERRED_MTU]) and requests a high connection priority for the handshake window
 * (downgraded to Balanced after [CONNECTION_PRIORITY_BOOST_MS]). Both are best-effort: a
 * failure leaves the OS defaults in place and never fails the connect.
 *
 * Example — bonded device (no fresh advertisement, must use `autoConnect`):
 * ```kotlin
 * val transport = BleTransport(address = "AA:BB:CC:DD:EE:FF") {
 *     autoConnectIf { true }
 * }
 * ```
 *
 * @param address Bluetooth MAC address string (e.g. `"AA:BB:CC:DD:EE:FF"`).
 * @param builderAction Optional [PeripheralBuilder] action for GATT configuration (threading,
 *   `autoConnect`, etc.). For bonded devices without a fresh advertisement, add
 *   `autoConnectIf { true }` to avoid GATT error 133.
 */
public fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport {
    val transport = BleTransport(
        peripheral = Peripheral(address.toIdentifier(), builderAction),
        address = address,
    )
    transport.postConnectHook = { connected ->
        val android = connected as? AndroidPeripheral
        if (android != null) {
            tryTune { android.requestMtu(PREFERRED_MTU) }
            tryTune { android.requestConnectionPriority(AndroidPeripheral.Priority.High) }
            launch {
                delay(CONNECTION_PRIORITY_BOOST_MS)
                tryTune { android.requestConnectionPriority(AndroidPeripheral.Priority.Balanced) }
            }
        }
    }
    return transport
}

/** Run a best-effort GATT tuning call: cancellation propagates, everything else is ignored. */
private inline fun tryTune(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // best-effort: leave OS defaults in place.
    }
}
