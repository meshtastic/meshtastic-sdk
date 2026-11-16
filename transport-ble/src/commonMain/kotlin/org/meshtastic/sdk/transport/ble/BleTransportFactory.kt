/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Creates a [BleTransport] for a radio found by [BleScanner.scan], on every target.
 *
 * Uses the advertisement the scan already holds, so no platform identifier handling is needed.
 * Android applies the same MTU and connection-priority tuning as `BleTransport(address = ...)`.
 *
 * @param radio a radio emitted by [BleScanner.scan].
 * @param parentContext parent coroutine context for the transport's internal scope.
 * @param builderAction optional [PeripheralBuilder] configuration (threading, `autoConnect`, ...).
 * @throws IllegalArgumentException if [radio] was not produced by [BleScanner.scan].
 * @since 0.3.0
 */
public fun BleTransport(
    radio: DiscoveredRadio,
    parentContext: CoroutineContext = EmptyCoroutineContext,
    builderAction: PeripheralBuilder.() -> Unit = {},
): BleTransport {
    val advertisement = requireNotNull(radio.advertisement) {
        "DiscoveredRadio must come from BleScanner.scan()"
    }
    val transport = BleTransport(
        peripheral = Peripheral(advertisement, builderAction),
        address = radio.address,
        parentContext = parentContext,
    )
    applyPlatformTuning(transport)
    return transport
}
