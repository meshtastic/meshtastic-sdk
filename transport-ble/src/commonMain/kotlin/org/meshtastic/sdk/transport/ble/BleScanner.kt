/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.meshtastic.sdk.MeshtasticException

/**
 * Finds Meshtastic radios over BLE.
 *
 * The SDK does no permission handling: the caller must hold these before collecting [scan].
 *
 * - **Android 12+ (API 31+):** `BLUETOOTH_SCAN` (declare `neverForLocation` if the app derives no
 *   location from scans) and, to connect to a result, `BLUETOOTH_CONNECT`.
 * - **Android 11 and older:** `ACCESS_FINE_LOCATION`, with location services on.
 * - **iOS:** `NSBluetoothAlwaysUsageDescription` in `Info.plist`; the system prompts on first use.
 * - **JVM:** a working BlueZ (Linux), CoreBluetooth (macOS) or WinRT (Windows) stack.
 * - **Web:** continuous scanning requires the browser's experimental Web Bluetooth scan API.
 *   Use the web target's `requestBleTransport()` for the browser's device chooser.
 *
 * If a requirement is missing, or Bluetooth is off or unavailable, collecting [scan] fails with
 * [MeshtasticException.Transport] whose `cause` is the underlying platform exception.
 *
 * @since 0.3.0
 */
public object BleScanner {

    /**
     * Scans for advertising Meshtastic radios while the returned flow is collected.
     *
     * Cold: each collection starts its own platform scan, filtered on
     * [BleConstants.MESH_SERVICE_UUID], and cancelling the collector stops it.
     *
     * Not deduplicated: every advertisement the platform delivers is emitted, so a UI can fold the
     * flow into a map keyed by [DiscoveredRadio.address] and keep RSSI current. On BlueZ the
     * first emissions are the devices the host already knows, bonded ones included, with a `null`
     * RSSI; treat a radio as present only once it arrives with a reading. The flow keeps no per-radio
     * state and adds no buffer, so memory stays constant however long it is collected, and a slow
     * collector backpressures the platform scan callback rather than queueing. Apply `conflate()`
     * or a sampler downstream when updates arrive faster than they are rendered.
     *
     * The flow does not complete on its own. It fails with [MeshtasticException.Transport] when the
     * platform scan cannot start or dies; see [BleScanner] for the permissions the caller must hold.
     *
     * @since 0.3.0
     */
    public fun scan(): Flow<DiscoveredRadio> = scanOf {
        Scanner {
            filters {
                match { services = listOf(BleConstants.MESH_SERVICE_UUID) }
            }
        }.advertisements.map { ad -> ad.toRawAdvertisement() }
    }
}

/**
 * The scan flow over [advertisements], which runs at collection: a platform scanner that cannot
 * be built fails the flow as [MeshtasticException.Transport] rather than throwing from `scan()`.
 */
internal fun scanOf(advertisements: () -> Flow<RawAdvertisement>): Flow<DiscoveredRadio> =
    flow { emitAll(advertisements()) }.asDiscoveredRadios()

/** Platform-neutral view of one advertisement, so the mapping is testable without Kable types. */
internal class RawAdvertisement(
    val address: String,
    val name: String?,
    val rssi: Int,
    val advertisement: PlatformAdvertisement? = null,
)

private fun PlatformAdvertisement.toRawAdvertisement(): RawAdvertisement = RawAdvertisement(
    // Identifier is a String on Android/JVM and a platform UUID type on Apple; a template covers both.
    address = "$identifier",
    name = name,
    rssi = rssi,
    advertisement = this,
)

internal fun RawAdvertisement.toDiscoveredRadio(): DiscoveredRadio = DiscoveredRadio(
    name = name?.takeIf { it.isNotBlank() },
    // Kable reports Int.MIN_VALUE when the platform gave no reading.
    rssi = rssi.takeIf { it != Int.MIN_VALUE },
    address = address,
    advertisement = advertisement,
)

/** Maps raw advertisements to the public type and wraps any scan failure in the module's taxonomy. */
internal fun Flow<RawAdvertisement>.asDiscoveredRadios(): Flow<DiscoveredRadio> = map { it.toDiscoveredRadio() }
    .catch { cause ->
        if (cause is CancellationException || cause is MeshtasticException) throw cause
        throw MeshtasticException.Transport("BLE scan failed: ${cause.message}", cause)
    }
