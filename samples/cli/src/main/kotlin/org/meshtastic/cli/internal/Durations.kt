/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

/**
 * Parses a duration literal like "30s", "5m", "2000ms", or a bare number (interpreted as ms).
 * Returns null on parse failure.
 */
internal fun parseDurationMs(raw: String): Long? {
    val s = raw.trim().lowercase()
    return when {
        s.endsWith("ms") -> s.removeSuffix("ms").toLongOrNull()
        s.endsWith("s") -> s.removeSuffix("s").toLongOrNull()?.let { it * 1_000L }
        s.endsWith("m") -> s.removeSuffix("m").toLongOrNull()?.let { it * 60_000L }
        s.endsWith("h") -> s.removeSuffix("h").toLongOrNull()?.let { it * 3_600_000L }
        else -> s.toLongOrNull()
    }
}
