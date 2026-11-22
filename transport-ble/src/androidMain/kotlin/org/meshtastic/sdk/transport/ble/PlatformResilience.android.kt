/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import android.bluetooth.BluetoothDevice
import com.juul.kable.AndroidPeripheral
import com.juul.kable.ExperimentalKableApi
import com.juul.kable.InternalKableApi
import com.juul.kable.NotConnectedException
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder

internal actual fun PeripheralBuilder.installAutoConnect(switch: AutoConnectSwitch) {
    autoConnectIf { switch.isOn }
}

// bluetoothDevice is Kable's only Context-free route to the BluetoothDevice; Kable is pinned exactly.
@OptIn(InternalKableApi::class)
internal actual fun applyPlatformResilience(transport: BleTransport, resilience: BleResilience) {
    val android = transport.peripheral as? AndroidPeripheral ?: return
    if (resilience.bondFirst) transport.bondControl = AndroidBondControl(android.bluetoothDevice)
    if (resilience.refreshStaleGattCache) {
        transport.gattCacheRefresher =
            GattCacheRefresher { android.refreshGattCacheOrFalse() }
    }
}

private class AndroidBondControl(private val device: BluetoothDevice) : BondControl {
    // Both calls need BLUETOOTH_CONNECT on API 31+; a SecurityException fails the connect with it named.
    @Suppress("MissingPermission")
    override fun progress(): BondProgress = when (device.bondState) {
        BluetoothDevice.BOND_BONDED -> BondProgress.Bonded
        BluetoothDevice.BOND_BONDING -> BondProgress.Bonding
        else -> BondProgress.None
    }

    @Suppress("MissingPermission")
    override fun create(): Boolean = device.createBond()
}

/** Kable's `refreshGattCache`, false when the platform declines it or the link is already gone. */
@OptIn(ExperimentalKableApi::class)
internal fun Peripheral.refreshGattCacheOrFalse(): Boolean = try {
    (this as? AndroidPeripheral)?.refreshGattCache() == true
} catch (_: NotConnectedException) {
    false
}
