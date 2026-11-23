/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import android.bluetooth.BluetoothDevice
import com.juul.kable.AndroidPeripheral
import com.juul.kable.InternalKableApi
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import com.juul.kable.toIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.MeshtasticException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One address-filtered scan window. */
internal val ADDRESS_SCAN_WINDOW: Duration = 5.seconds

/** Scan windows an unbonded radio gets before the connect fails. */
internal const val UNBONDED_SCAN_ATTEMPTS: Int = 3

/** Pause between an unbonded radio's scan windows. */
internal val ADDRESS_SCAN_RETRY_DELAY: Duration = 1.seconds

/**
 * Finds the radio at [address] before a connect, by the rule Meshtastic-Android's
 * `BleDeviceLocator` uses: a bonded radio gets one [ADDRESS_SCAN_WINDOW], then falls back to its
 * bonded handle; an unbonded one gets [UNBONDED_SCAN_ATTEMPTS] windows [ADDRESS_SCAN_RETRY_DELAY]
 * apart, then fails as out of range.
 *
 * [scanOnce] returns the first advertisement from [address]; a window that times out, completes
 * empty or fails counts as a miss. One window for a bonded radio keeps each reconnect to a single
 * Android scanner registration, clear of `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`.
 *
 * @throws MeshtasticException.Transport when an unbonded radio is not heard in any window; the last
 *   scan failure, if any, is the cause.
 */
internal suspend fun <A : Any> locateRadio(
    address: String,
    bonded: Boolean,
    scanOnce: suspend () -> A?,
): LocatedRadio<A> {
    val attempts = if (bonded) 1 else UNBONDED_SCAN_ATTEMPTS
    var lastFailure: Exception? = null
    repeat(attempts) { attempt ->
        if (attempt > 0) delay(ADDRESS_SCAN_RETRY_DELAY)
        try {
            withTimeoutOrNull(ADDRESS_SCAN_WINDOW) { scanOnce() }?.let { return LocatedRadio.Advertised(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailure = e
        }
    }
    if (bonded) return LocatedRadio.BondedHandle
    throw MeshtasticException.Transport(
        "No BLE advertisement from $address in $attempts scans of $ADDRESS_SCAN_WINDOW; " +
            "the radio is out of range, off or not advertising",
        lastFailure,
    )
}

/**
 * A transport that runs [locateRadio] in every [BleTransport.connect] and builds the peripheral
 * from the result through [createPeripheral]. [isBonded] is read at each connect, so a bond made
 * since the last one counts.
 */
internal fun <A : Any> createScanFirstBleTransport(
    address: String,
    resilience: BleResilience,
    builderAction: PeripheralBuilder.() -> Unit,
    isBonded: () -> Boolean,
    scanOnce: suspend () -> A?,
    createPeripheral: (LocatedRadio<A>, PeripheralBuilder.() -> Unit) -> Peripheral,
): BleTransport = createDeferredBleTransport(address, builderAction, resilience, resolveOnEveryConnect = true) {
    createPeripheral(locateRadio(address, isBonded(), scanOnce), it)
}

/** The scan-first transport over Kable's scanner and the host's bond list. */
internal fun createAndroidScanFirstBleTransport(
    address: String,
    resilience: BleResilience,
    builderAction: PeripheralBuilder.() -> Unit,
): BleTransport = createScanFirstBleTransport(
    address = address,
    resilience = resilience,
    builderAction = builderAction,
    isBonded = { isBondedOnHost(address) },
    scanOnce = { scanForAddress(address) },
) { located, configure ->
    when (located) {
        is LocatedRadio.Advertised -> Peripheral(located.advertisement, configure)
        LocatedRadio.BondedHandle -> Peripheral(address.toIdentifier(), configure)
    }
}

/**
 * True when the host reports [address] as bonded (`BluetoothDevice.getBondState()`). Without
 * `BLUETOOTH_CONNECT` the state is unreadable and the radio is treated as unbonded.
 */
// bluetoothDevice is Kable's only Context-free route to the BluetoothDevice; Kable is pinned exactly.
@OptIn(InternalKableApi::class)
@Suppress("MissingPermission") // BLUETOOTH_CONNECT is the caller's; a SecurityException reads as unbonded
private fun isBondedOnHost(address: String): Boolean {
    val probe = Peripheral(address.toIdentifier())
    return try {
        (probe as? AndroidPeripheral)?.bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDED
    } catch (_: SecurityException) {
        false
    } finally {
        probe.close()
    }
}

/** The first advertisement from [address], from a scan Android filters on the address natively. */
private suspend fun scanForAddress(address: String): PlatformAdvertisement {
    // Android's ScanFilter rejects a MAC that is not upper case.
    val mac = address.uppercase()
    return Scanner { filters { match { this.address = mac } } }
        .advertisements
        .first { it.identifier.equals(mac, ignoreCase = true) }
}

/** What [locateRadio] found for an address. */
internal sealed interface LocatedRadio<out A> {
    /** Heard in a scan: connect from [advertisement]. */
    class Advertised<A>(val advertisement: A) : LocatedRadio<A>

    /** Bonded but not heard: connect by address and let the platform wait for it. */
    data object BondedHandle : LocatedRadio<Nothing>
}
