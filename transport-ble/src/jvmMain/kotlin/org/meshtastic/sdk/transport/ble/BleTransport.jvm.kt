/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.toIdentifier
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Creates a BLE transport using a saved Kable identifier on desktop. Use the identifier
 * returned by discovery on this host. [BleTransport.shutdown] closes the peripheral.
 */
public fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport =
    createBleTransport(address, EmptyCoroutineContext, BleResilience.None, builderAction) { configure ->
        Peripheral(address.toIdentifier(), configure)
    }
