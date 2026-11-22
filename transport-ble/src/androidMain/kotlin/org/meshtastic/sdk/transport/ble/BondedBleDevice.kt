/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juul.kable.PeripheralBuilder
import org.meshtastic.sdk.MeshtasticException

/**
 * A device the Android host has bonded (paired) with. Android keeps the bond list per host, not
 * per app, so this is every bonded device, not only Meshtastic radios: the platform exposes no
 * reliable marker (an LE device's cached service UUIDs are often absent, and a radio's name is
 * user-editable). Let the user pick, or match [address] against one you stored.
 *
 * @since 0.3.0
 */
public class BondedBleDevice internal constructor(
    /** The advertised or cached name; `null` when the host has none. */
    public val name: String?,
    /** The MAC address, the same string [BleTransport] takes as `address`. */
    public val address: String,
) {
    override fun equals(other: Any?): Boolean =
        other is BondedBleDevice && other.address == address && other.name == name

    override fun hashCode(): Int = 31 * address.hashCode() + (name?.hashCode() ?: 0)

    override fun toString(): String = "BondedBleDevice(name=$name, address=$address)"
}

/**
 * Lists the devices this Android host has bonded with, without scanning.
 *
 * A bond is not presence: a listed radio may be off or out of range, and the list carries no RSSI.
 * Connecting to one that is away fails like any other connect to an absent radio. For radios that
 * are actually advertising, use [BleScanner.scan]. (On Linux/BlueZ the scanner already emits known
 * devices first, bonded ones included, with a `null` RSSI.)
 *
 * Requires `BLUETOOTH_CONNECT` on Android 12+ (API 31+); earlier versions need no runtime permission.
 * The SDK does not request it.
 *
 * Android only: iOS has no bonded-device list (CoreBluetooth can only retrieve peripherals by
 * identifiers the app stored itself), and the JVM backends expose none through Kable.
 *
 * @param context any [Context]; only its application context is kept for the call.
 * @return the bonded devices, empty when none are bonded.
 * @throws MeshtasticException.Transport when the device has no Bluetooth adapter, Bluetooth is off,
 *   or the caller lacks `BLUETOOTH_CONNECT`.
 * @since 0.3.0
 */
public fun bondedBleDevices(context: Context): List<BondedBleDevice> {
    val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw MeshtasticException.Transport("Bluetooth is unavailable on this device")
    val bonded = try {
        @Suppress("MissingPermission") // documented requirement; a SecurityException is wrapped below
        adapter.bondedDevices.map { it.name to it.address }
    } catch (e: SecurityException) {
        throw MeshtasticException.Transport("Listing bonded devices needs BLUETOOTH_CONNECT: ${e.message}", e)
    }
    return bonded.toBondedBleDevices()
}

internal fun Collection<Pair<String?, String>>.toBondedBleDevices(): List<BondedBleDevice> = this
    .distinctBy { (_, address) -> address.uppercase() }
    .map { (name, address) -> BondedBleDevice(name = name?.takeIf { it.isNotBlank() }, address = address) }

/**
 * Builds a [BleTransport] for a bonded device. Equivalent to `BleTransport(device.address, resilience)`.
 *
 * @since 0.3.0
 */
public fun BleTransport(
    device: BondedBleDevice,
    resilience: BleResilience = BleResilience.None,
    builderAction: PeripheralBuilder.() -> Unit = {},
): BleTransport = BleTransport(device.address, resilience, builderAction)
