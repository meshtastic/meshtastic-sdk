/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

/**
 * Formats a float the way the JVM does, keeping a trailing `.0` on integral values.
 *
 * Kotlin/JS prints `5f` as `5` while the JVM prints `5.0`; this keeps formatted output identical
 * across targets.
 */
internal fun Float.toDecimalString(): String {
    val text = toString()
    return if (text.all { it.isDigit() || it == '-' }) "$text.0" else text
}
