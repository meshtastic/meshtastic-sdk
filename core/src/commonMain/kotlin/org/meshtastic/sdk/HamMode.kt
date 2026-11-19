/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Config
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.Routing

/**
 * Enables licensed (ham) operation without resetting the radio's TX power or frequency.
 *
 * Firmware applies `tx_power` and `frequency` from the request to the LoRa config
 * unconditionally, so [AdminApi.setHamMode] with those fields left at 0 sets TX power to 0 (the
 * regional maximum) and clears `override_frequency`. This call fills [txPower] and [frequency]
 * from the loaded LoRa config when they are null, so only the call sign and names change.
 * Pass a value to set it explicitly. A blank [shortName] or [longName] is omitted, which
 * firmware treats as "keep the current name".
 *
 * @return [AdminResult.Failed] with [Routing.Error.BAD_REQUEST], nothing sent, when [txPower] or
 *   [frequency] is null and the LoRa config has not loaded yet. Otherwise the result of
 *   [AdminApi.setHamMode].
 * @since 0.3.0
 */
public suspend fun RadioClient.enableHamMode(
    callSign: String,
    shortName: String? = null,
    longName: String? = null,
    txPower: Int? = null,
    frequency: Float? = null,
): AdminResult<Unit> {
    val lora = configBundle.value?.configs?.firstNotNullOfOrNull { it.lora }
    val params = hamParameters(callSign, shortName, longName, txPower, frequency, lora)
        ?: return AdminResult.Failed(Routing.Error.BAD_REQUEST)
    return admin.setHamMode(params)
}

internal fun hamParameters(
    callSign: String,
    shortName: String?,
    longName: String?,
    txPower: Int?,
    frequency: Float?,
    lora: Config.LoRaConfig?,
): HamParameters? {
    if ((txPower == null || frequency == null) && lora == null) return null
    return HamParameters.Builder().also { wb ->
        wb.call_sign = callSign
        wb.short_name = shortName.orEmpty()
        wb.long_name = longName.orEmpty()
        wb.tx_power = txPower ?: lora!!.tx_power
        wb.frequency = frequency ?: lora!!.override_frequency
    }.build()
}
