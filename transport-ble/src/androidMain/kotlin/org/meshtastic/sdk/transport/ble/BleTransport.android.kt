/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.PeripheralBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/**
 * How long to hold [AndroidPeripheral.Priority.High] after connecting before downgrading to
 * [AndroidPeripheral.Priority.Balanced]. Covers the config handshake window (faster connection
 * interval ≈ faster handshake) without holding the radio at high duty cycle forever.
 */
private const val CONNECTION_PRIORITY_BOOST_MS: Long = 30_000L

/**
 * Android-specific factory that creates a [BleTransport] from a persisted MAC address string.
 *
 * Each [BleTransport.connect] finds the radio by a scan filtered on [address] before it connects,
 * because a direct connect by bare MAC fails while Android's device cache is empty, as it is after
 * Bluetooth restarts. A bonded radio gets one 5 s scan, then a connect through its bonded handle;
 * an unbonded radio gets three 5 s scans 1 s apart, after which `connect()` fails with
 * [org.meshtastic.sdk.MeshtasticException.Transport]. The bond state is read at each connect, and
 * an unreadable one (no `BLUETOOTH_CONNECT`) counts as unbonded. The scan needs `BLUETOOTH_SCAN`
 * on Android 12+ (API 31+), location before that; a scan that cannot run counts as a miss. Pass
 * `BleResilience(autoConnectFallback = true)` so a bonded radio that missed its scan can be waited
 * for with Android's `autoConnect`.
 *
 * Kable requests an ATT MTU of 517 as each link is established (`PeripheralBuilder.mtu`, its
 * Android default): ToRadio/FromRadio frames are up to 512 bytes, and Android otherwise stays at
 * the 23-byte minimum. After each link the transport also requests a high connection priority for
 * the handshake window (downgraded to Balanced after [CONNECTION_PRIORITY_BOOST_MS]; the pending
 * downgrade is cancelled if the session disconnects first). That request is best-effort: a
 * failure leaves the OS default in place and never fails the connect.
 *
 * Example:
 * ```kotlin
 * val transport = BleTransport(address = "AA:BB:CC:DD:EE:FF")
 * ```
 *
 * @param address Bluetooth MAC address string (e.g. `"AA:BB:CC:DD:EE:FF"`).
 * @param builderAction Optional [PeripheralBuilder] action for GATT configuration (threading,
 *   `autoConnect`, etc.).
 */
public fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport =
    BleTransport(address, BleResilience.None, builderAction)

/**
 * Same as `BleTransport(address, builderAction)`, scan first included, with opt-in link recovery:
 * see [BleResilience].
 *
 * @param address Bluetooth MAC address string (e.g. `"AA:BB:CC:DD:EE:FF"`).
 * @param resilience the mechanisms to enable; [BleResilience.None] reproduces the plain factory.
 * @param builderAction Optional [PeripheralBuilder] action; runs after the resilience wiring, so an
 *   `autoConnectIf` set here takes precedence.
 * @since 0.3.0
 */
public fun BleTransport(
    address: String,
    resilience: BleResilience,
    builderAction: PeripheralBuilder.() -> Unit = {},
): BleTransport = createAndroidScanFirstBleTransport(address, resilience, builderAction)

internal actual fun applyPlatformTuning(transport: BleTransport) {
    transport.postConnectHook = { connected ->
        val android = connected as? AndroidPeripheral
        if (android != null) {
            tryTune { android.requestConnectionPriority(AndroidPeripheral.Priority.High) }
            // Receiver scope is per-connect-cycle: this delayed downgrade is cancelled at
            // disconnect(), so a stale timer can never fire into a later session's boost window.
            launch {
                delay(CONNECTION_PRIORITY_BOOST_MS)
                tryTune { android.requestConnectionPriority(AndroidPeripheral.Priority.Balanced) }
            }
        }
    }
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
