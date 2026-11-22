/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.PlatformAdvertisement

/**
 * One advertisement from a Meshtastic radio, as emitted by [BleScanner.scan].
 *
 * Immutable snapshot: a radio that keeps advertising produces a new instance per advertisement,
 * so equal [address] values with different [rssi] are the same radio seen again.
 *
 * Turn a pick into a transport with `BleTransport(radio)`; the Kable advertisement behind it
 * stays internal.
 *
 * @property name Advertised local name (for example `Meshtastic_ab12`), or `null` when this
 *   advertisement did not carry one.
 * @property rssi Received signal strength in dBm (negative; closer to zero is stronger), or `null`
 *   when the platform reports none. BlueZ emits every device the host already knows at scan
 *   start with no reading, whether or not it is on air, so `null` there means "not heard in this
 *   scan yet".
 * @property address Stable platform identifier: the Bluetooth MAC address on Android, the
 *   CoreBluetooth peripheral UUID string on iOS, and on the JVM the Kable identifier's string form:
 *   on Linux the BlueZ object path wrapped in JSON (`{"object_path":"/org/bluez/hci0/dev_..."}`),
 *   on Windows the MAC, on macOS a CoreBluetooth UUID. It is the value the platform `BleTransport`
 *   storage identity is keyed on, and on Android and the JVM it can be passed to
 *   `BleTransport(address = ...)`.
 * @since 0.3.0
 */
public class DiscoveredRadio internal constructor(
    public val name: String?,
    public val rssi: Int?,
    public val address: String,
    internal val advertisement: PlatformAdvertisement?,
) {
    override fun equals(other: Any?): Boolean =
        other is DiscoveredRadio && other.address == address && other.name == name && other.rssi == rssi

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (rssi ?: 0)
        return result
    }

    override fun toString(): String = "DiscoveredRadio(name=$name, rssi=$rssi, address=$address)"
}
