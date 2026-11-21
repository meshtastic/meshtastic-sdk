/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

/** `HOP_RELIABLE` in `firmware:src/mesh/MeshTypes.h`, what the firmware uses for `lora.hop_limit = 0`. */
private const val FIRMWARE_DEFAULT_HOP_LIMIT: Int = 3

/**
 * The device's `lora.hop_limit` once the handshake has delivered it, else the firmware default.
 * Firmware substitutes its own default for a `0` only when `want_ack` is set.
 */
internal fun MeshEngine.defaultHopLimit(): Int {
    val configured = configBundleState.value
        ?.configs
        ?.firstNotNullOfOrNull { it.lora }
        ?.hop_limit
        ?: 0
    return if (configured > 0) configured else FIRMWARE_DEFAULT_HOP_LIMIT
}
