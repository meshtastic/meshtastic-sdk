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
 * @param resilience opt-in link-recovery mechanisms; Android only, see [BleResilience].
 * @param builderAction optional [PeripheralBuilder] configuration (threading, `autoConnect`, ...).
 * @throws IllegalArgumentException if [radio] was not produced by [BleScanner.scan].
 * @since 0.3.0
 */
public fun BleTransport(
    radio: DiscoveredRadio,
    parentContext: CoroutineContext = EmptyCoroutineContext,
    resilience: BleResilience = BleResilience.None,
    builderAction: PeripheralBuilder.() -> Unit = {},
): BleTransport {
    val advertisement = requireNotNull(radio.advertisement) {
        "DiscoveredRadio must come from BleScanner.scan()"
    }
    return createBleTransport(radio.address, parentContext, resilience, builderAction) { configure ->
        Peripheral(advertisement, configure)
    }
}

/** Builds the peripheral through [createPeripheral], then wires platform tuning and [resilience]. */
internal fun createBleTransport(
    address: String,
    parentContext: CoroutineContext,
    resilience: BleResilience,
    builderAction: PeripheralBuilder.() -> Unit,
    createPeripheral: (PeripheralBuilder.() -> Unit) -> Peripheral,
): BleTransport {
    val switch = if (resilience.autoConnectFallback) AutoConnectSwitch() else null
    val peripheral = createPeripheral {
        // The caller's builderAction runs last so its own autoConnectIf wins.
        switch?.let { installAutoConnect(it) }
        builderAction()
    }
    val transport = BleTransport(peripheral = peripheral, address = address, parentContext = parentContext)
    transport.ownsPeripheral = true
    transport.autoConnectSwitch = switch
    applyPlatformTuning(transport)
    applyPlatformResilience(transport, peripheral, resilience)
    return transport
}

/**
 * Like [createBleTransport], but [resolvePeripheral] runs in [BleTransport.connect] instead of
 * here, for a peripheral that has to be found first: in the first connect only, or in every
 * connect when [resolveOnEveryConnect] is set. [resilience] is wired onto each resolved peripheral.
 */
internal fun createDeferredBleTransport(
    address: String,
    builderAction: PeripheralBuilder.() -> Unit,
    resilience: BleResilience = BleResilience.None,
    resolveOnEveryConnect: Boolean = false,
    resolvePeripheral: suspend (PeripheralBuilder.() -> Unit) -> Peripheral,
): BleTransport {
    val switch = if (resilience.autoConnectFallback) AutoConnectSwitch() else null
    val configure: PeripheralBuilder.() -> Unit = {
        // The caller's builderAction runs last so its own autoConnectIf wins.
        switch?.let { installAutoConnect(it) }
        builderAction()
    }
    lateinit var transport: BleTransport
    transport = BleTransport(
        peripheral = null,
        address = address,
        parentContext = EmptyCoroutineContext,
        resolvePeripheral = {
            resolvePeripheral(configure).also { applyPlatformResilience(transport, it, resilience) }
        },
        resolveOnEveryConnect = resolveOnEveryConnect,
    )
    transport.ownsPeripheral = true
    transport.autoConnectSwitch = switch
    applyPlatformTuning(transport)
    return transport
}
