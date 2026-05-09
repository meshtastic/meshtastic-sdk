/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.sdk.NodeId

/**
 * Returns the 8-character hex representation of this [NodeId] (e.g., `"aabbccdd"`).
 * Does not include the `!` or `0x` prefix.
 */
public fun NodeId.toHex(): String {
    val hex = (raw.toLong() and 0xFFFFFFFFL).toString(16)
    return "0".repeat(8 - hex.length) + hex
}

/**
 * Parses an 8-character hex string (optionally prefixed with `!` or `0x`) into a [NodeId].
 * Returns `null` if the string is not a valid 8-character hex value.
 */
public fun NodeId.Companion.fromHex(text: String): NodeId? {
    val cleaned = text.trim().removePrefix("!").removePrefix("0x").removePrefix("0X")
    if (cleaned.length != 8) return null
    val parsed = cleaned.toLongOrNull(16) ?: return null
    return NodeId(parsed.toInt())
}

/** Returns `true` if this is [NodeId.BROADCAST]. */
public val NodeId.isBroadcast: Boolean get() = this == NodeId.BROADCAST

/**
 * Returns `true` if this is [NodeId.LOCAL] or matches the supplied [own] ID.
 * Useful for filtering packets originating from the host.
 */
public fun NodeId.isLocal(own: NodeId? = null): Boolean = this == NodeId.LOCAL || (own != null && this == own)

/** Returns `true` if this is a specific node address (neither local nor broadcast). */
public val NodeId.isUnicast: Boolean get() = this != NodeId.BROADCAST && this != NodeId.LOCAL

/** Returns the Meshtastic default ID format: `"!aabbccdd"` (lowercase hex with `!` prefix). */
public fun NodeId.toDefaultId(): String = "!" + toHex()

/** Parses a default ID string (`"!aabbccdd"`) into a [NodeId]. Delegates to [fromHex]. */
public fun NodeId.Companion.fromDefaultId(id: String): NodeId? = fromHex(id)
