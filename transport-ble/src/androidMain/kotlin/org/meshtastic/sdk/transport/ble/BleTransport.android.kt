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
import kotlin.coroutines.EmptyCoroutineContext

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
 * (downgraded to Balanced after [CONNECTION_PRIORITY_BOOST_MS]; the pending downgrade is
 * cancelled if the session disconnects first). Both are best-effort: a failure leaves the
 * OS defaults in place and never fails the connect.
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
public fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport =
    BleTransport(address, BleResilience.None, builderAction)

/**
 * Same as `BleTransport(address, builderAction)` with opt-in link recovery: see [BleResilience].
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
): BleTransport = createBleTransport(address, EmptyCoroutineContext, resilience, builderAction) { configure ->
    Peripheral(address.toIdentifier(), configure)
}

/**
 * Creates a transport that can refresh Android's GATT cache after a firmware update, even when
 * discovery returns all required characteristics.
 *
 * [refreshGattCacheOnConnect] is called once after the link connects and before PhoneAPI I/O
 * begins. Return true to attempt an immediate cache refresh and reconnect for rediscovery. A
 * failed initial link leaves the callback unread, and a platform refusal keeps the existing link.
 * If stale-cache repair is also enabled in [resilience], both triggers share one refresh per
 * link. The callback must return promptly and must not perform I/O.
 */
public fun BleTransport(
    address: String,
    resilience: BleResilience,
    refreshGattCacheOnConnect: () -> Boolean,
    builderAction: PeripheralBuilder.() -> Unit,
): BleTransport = BleTransport(address, resilience, builderAction).also { transport ->
    transport.gattCacheRefresher = GattCacheRefresher { transport.peripheral.refreshHiddenGattCache() }
    transport.shouldRefreshGattCache = { discoveryIncomplete ->
        refreshGattCacheOnConnect() || (resilience.refreshStaleGattCache && discoveryIncomplete)
    }
}

internal actual fun applyPlatformTuning(transport: BleTransport) {
    transport.postConnectHook = { connected ->
        val android = connected as? AndroidPeripheral
        if (android != null) {
            tryTune { android.requestMtu(PREFERRED_MTU) }
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
