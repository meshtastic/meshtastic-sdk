/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The first radio bonded to this phone that is advertising within [windowMs], or `null`.
 *
 * Bonded and advertising, because the first bonded device may be switched off and an unbonded radio
 * may need a pairing dialog no test can answer.
 */
internal suspend fun advertisingBondedRadio(context: Context, windowMs: Long = 15_000L): BondedBleDevice? {
    val bonded = bondedBleDevices(context).associateBy { it.address.uppercase() }
    if (bonded.isEmpty()) return null
    val seen = withTimeoutOrNull(windowMs) {
        BleScanner.scan().first { it.address.uppercase() in bonded }
    } ?: return null
    return bonded[seen.address.uppercase()]
}
