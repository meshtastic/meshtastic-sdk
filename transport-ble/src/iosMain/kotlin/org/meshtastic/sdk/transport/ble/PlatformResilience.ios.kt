/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.PeripheralBuilder

internal actual fun applyPlatformResilience(transport: BleTransport, resilience: BleResilience) = Unit

internal actual fun PeripheralBuilder.installAutoConnect(switch: AutoConnectSwitch) = Unit
