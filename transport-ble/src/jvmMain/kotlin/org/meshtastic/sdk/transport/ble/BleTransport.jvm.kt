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
 * Creates a BLE transport on desktop from a saved address.
 *
 * [address] is either the Kable identifier [BleScanner.scan] reported on this host
 * ([DiscoveredRadio.address]) or a Bluetooth MAC such as `"C2:5D:2D:C4:E5:6E"`.
 *
 * Kable's identifier differs per OS: on Linux it is BlueZ's device path wrapped in JSON,
 * `{"object_path":"/org/bluez/hci0/dev_C2_5D_2D_C4_E5_6E"}`; on Windows it is the MAC; on macOS
 * it is a CoreBluetooth UUID, and macOS exposes no MAC at all. A valid identifier for this OS is
 * used directly. Anything else is resolved by the first [BleTransport.connect], which scans for
 * up to 15 seconds and connects from the first advertisement whose identifier matches [address],
 * either exactly or by MAC. When none does, `connect()` fails with
 * [org.meshtastic.sdk.MeshtasticException.Transport]. The string reaches Kable's native layer
 * only once it is known to parse there.
 *
 * [BleTransport.shutdown] closes the peripheral.
 *
 * @param address a [DiscoveredRadio.address] from this host, or a Bluetooth MAC address.
 * @param builderAction optional [PeripheralBuilder] configuration.
 */
public fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport =
    if (isKableIdentifier(address, desktopBleOs())) {
        createBleTransport(address, EmptyCoroutineContext, BleResilience.None, builderAction) { configure ->
            Peripheral(address.toIdentifier(), configure)
        }
    } else {
        createDeferredBleTransport(address, builderAction) { configure ->
            val radio = findAdvertisedRadio(address, BleScanner.scan())
            // BleScanner.scan() always attaches the advertisement.
            Peripheral(checkNotNull(radio.advertisement), configure)
        }
    }
