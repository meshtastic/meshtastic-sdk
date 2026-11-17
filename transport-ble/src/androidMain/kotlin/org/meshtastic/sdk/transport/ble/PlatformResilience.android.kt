/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.juul.kable.AndroidPeripheral
import com.juul.kable.InternalKableApi
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import kotlinx.coroutines.flow.StateFlow

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
            GattCacheRefresher { android.refreshHiddenGattCache() }
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

/**
 * Calls the hidden `BluetoothGatt.refresh()` on the live connection. Kable keeps the gatt in private
 * fields (`connection` on the peripheral, `gatt` on the connection); consumer-rules.pro keeps both
 * names. False when either hop or the method is unreachable, e.g. a Kable bump or the hidden-API blocklist.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun Peripheral.refreshHiddenGattCache(): Boolean = try {
    val connection = (readField(this, "connection") as? StateFlow<*>)?.value
    val gatt = connection?.let { readField(it, "gatt") } as? BluetoothGatt
    gatt != null && (gatt.javaClass.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(gatt) == true)
} catch (e: Exception) {
    false
}

private fun readField(target: Any, name: String): Any? {
    var type: Class<*>? = target.javaClass
    while (type != null && type != Any::class.java) {
        try {
            return type.getDeclaredField(name).apply { isAccessible = true }.get(target)
        } catch (_: NoSuchFieldException) {
            type = type.superclass
        }
    }
    return null
}
