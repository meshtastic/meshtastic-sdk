/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

/**
 * Opt-in link-recovery mechanisms for [BleTransport]. Every option defaults to off, which is the
 * behavior of a transport built without a [BleResilience].
 *
 * Android only: the options map to `BluetoothDevice` and `BluetoothGatt` behavior that iOS and
 * the JVM backends do not have. On those targets a [BleResilience] is accepted and ignored.
 *
 * None of these prompts the user or touches app lifecycle. [bondFirst] asks the OS to pair, which
 * the OS itself surfaces as a system dialog; the app needs `BLUETOOTH_CONNECT` on Android 12+.
 *
 * @property bondFirst when the device is not bonded, ask the OS to bond before the GATT connect and
 *   wait for it (30 s), publishing [org.meshtastic.sdk.TransportState.Bonding] meanwhile. A bond
 *   that fails or is rejected fails the connect. Without it, bonding starts reactively on the first
 *   encrypted read.
 * @property autoConnectFallback when a direct connect does not complete within 15 s, retry with
 *   Android's `autoConnect = true`, which succeeds against a bonded radio whose address or
 *   timing defeats the direct path. The next [BleTransport.connect] starts direct again. A
 *   `builderAction` that sets its own `autoConnectIf` overrides this.
 * @property refreshStaleGattCache when a connect finds the Meshtastic service or one of its
 *   characteristics missing from discovery, refresh Android's cached GATT table (a hidden
 *   `BluetoothGatt` method reached by reflection into Kable 0.45 internals) and reconnect once to
 *   rediscover. Best-effort: when the platform refuses the refresh the connect proceeds unchanged.
 * @property refreshGattCacheAfterFailures after this many failures in a row (a connect that throws,
 *   or a link that drops within 5 s of connecting), refresh the cached GATT table on the next
 *   connect that reaches the radio, the same way as [refreshStaleGattCache]. Once per failure
 *   streak: a link that stays up for 5 s ends the streak and re-arms it, and a refresh the platform
 *   refuses does not use it up. A stale cache after a radio was away for a long time otherwise
 *   fails every reconnect until the user unpairs. `null` (the default) turns it off.
 * @since 0.3.0
 */
public class BleResilience(
    public val bondFirst: Boolean = false,
    public val autoConnectFallback: Boolean = false,
    public val refreshStaleGattCache: Boolean = false,
    public val refreshGattCacheAfterFailures: Int? = null,
) {
    init {
        require(refreshGattCacheAfterFailures == null || refreshGattCacheAfterFailures > 0) {
            "refreshGattCacheAfterFailures must be > 0 when set"
        }
    }

    override fun equals(other: Any?): Boolean = other is BleResilience &&
        other.bondFirst == bondFirst &&
        other.autoConnectFallback == autoConnectFallback &&
        other.refreshStaleGattCache == refreshStaleGattCache &&
        other.refreshGattCacheAfterFailures == refreshGattCacheAfterFailures

    override fun hashCode(): Int =
        ((bondFirst.hashCode() * 31 + autoConnectFallback.hashCode()) * 31 + refreshStaleGattCache.hashCode()) * 31 +
            (refreshGattCacheAfterFailures ?: 0)

    override fun toString(): String = "BleResilience(bondFirst=$bondFirst, autoConnectFallback=$autoConnectFallback, " +
        "refreshStaleGattCache=$refreshStaleGattCache, refreshGattCacheAfterFailures=$refreshGattCacheAfterFailures)"

    public companion object {
        /** Every mechanism off. */
        public val None: BleResilience = BleResilience()
    }
}
