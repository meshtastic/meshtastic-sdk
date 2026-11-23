/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder

/**
 * Wires the platform half of [BleResilience] for [peripheral] onto [transport]; no-op where the
 * mechanism does not exist. Runs again for each peripheral a deferred transport resolves.
 */
internal expect fun applyPlatformResilience(transport: BleTransport, peripheral: Peripheral, resilience: BleResilience)

/** Makes the platform connect read [switch] for its `autoConnect` decision; no-op off Android. */
internal expect fun PeripheralBuilder.installAutoConnect(switch: AutoConnectSwitch)
