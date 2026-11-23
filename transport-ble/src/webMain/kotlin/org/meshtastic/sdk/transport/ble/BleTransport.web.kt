/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.ExperimentalKableApi
import com.juul.kable.Options
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.requestPeripheral
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Opens the browser's Web Bluetooth chooser, filtered to radios advertising the Meshtastic
 * service. Call directly from a user gesture on a secure page. Returns null when dismissed.
 * The selected peripheral is owned by the transport and released by [BleTransport.shutdown].
 *
 * Use the [BleTransport] constructor when the host already holds a Kable peripheral grant.
 */
@OptIn(ExperimentalKableApi::class)
public suspend fun requestBleTransport(
    parentContext: CoroutineContext = EmptyCoroutineContext,
    builderAction: PeripheralBuilder.() -> Unit = {},
): BleTransport? {
    val options = Options { filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } } }
    val peripheral = requestPeripheral(options, builderAction) ?: return null
    return BleTransport(peripheral, peripheral.identifier, parentContext).also { it.ownsPeripheral = true }
}

internal actual fun applyPlatformTuning(transport: BleTransport) = Unit

internal actual fun applyPlatformResilience(
    transport: BleTransport,
    peripheral: Peripheral,
    resilience: BleResilience,
) = Unit

internal actual fun PeripheralBuilder.installAutoConnect(switch: AutoConnectSwitch) = Unit
