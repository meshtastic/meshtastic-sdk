/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString

/** Firmware fills `MyNodeInfo.device_id` as a full 16-byte buffer, or leaves it empty. */
internal const val DEVICE_ID_LENGTH = 16

/**
 * [raw] when it can identify one physical device: exactly [DEVICE_ID_LENGTH] bytes and not all
 * zero. Firmware leaves the id empty on hardware without a unique id and zeroes it for
 * unauthenticated clients in lockdown mode, so callers must treat `null` as "unknown", never as a
 * match.
 */
internal fun trustedDeviceIdOrNull(raw: ByteString?): ByteString? {
    if (raw == null || raw.size != DEVICE_ID_LENGTH) return null
    for (i in 0 until raw.size) if (raw[i].toInt() != 0) return raw
    return null
}
