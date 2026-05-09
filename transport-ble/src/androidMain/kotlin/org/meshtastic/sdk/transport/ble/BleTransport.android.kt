/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.toIdentifier

/**
 * Android-specific factory that creates a [BleTransport] from a persisted MAC address string.
 *
 * On Android, Kable's `Identifier` is a type alias for `String`, but [toIdentifier] is the
 * canonical conversion and remains correct across Kable versions.
 *
 * Example — bonded device (no fresh advertisement, must use `autoConnect`):
 * ```kotlin
 * val transport = BleTransport(address = "AA:BB:CC:DD:EE:FF") {
 *     autoConnectIf { true }
 * }
 * ```
 *
 * @param address Bluetooth MAC address string (e.g. `"AA:BB:CC:DD:EE:FF"`).
 * @param builderAction Optional [PeripheralBuilder] action for GATT configuration (MTU, threading,
 *   `autoConnect`, etc.). For bonded devices without a fresh advertisement, add
 *   `autoConnectIf { true }` to avoid GATT error 133.
 */
public fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport = BleTransport(
    peripheral = Peripheral(address.toIdentifier(), builderAction),
    address = address,
)
