/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * Type-safe wrapper for Meshtastic node identifiers (uint32).
 *
 * The wire format is uint32; the full 32-bit range is permitted. The backing field is a
 * Kotlin `Int` interpreted as an unsigned 32-bit value, so any bit pattern is valid — no
 * additional validation is performed.
 *
 * Reserved values:
 * - `0x00000000` → [LOCAL] (the local node, "this radio")
 * - `0xFFFFFFFF` → [BROADCAST] (all nodes on the mesh)
 *
 * **Validation note:** value-class `init` blocks only run from Kotlin call sites; bytecode
 * constructed via reflection or generated code can bypass them. Because [NodeId] permits the
 * full uint32 range there is no `init` check, so this is moot in practice.
 *
 * **Java interop:** properties returning [NodeId] expose non-mangled boxed
 * accessors (e.g. `node.getNodeId()` rather than `getNodeId-PPSJZE4()`) thanks
 * to `-Xjvm-expose-boxed` (see KmpLibraryConventionPlugin). From
 * Java: `nodeId.getRaw()` to read the underlying `int`.
 *
 * @since 0.1.0
 */
@kotlin.jvm.JvmInline
public value class NodeId(public val raw: Int) {
    /** Renders as the canonical Meshtastic `!aabbccdd` user-id string. */
    override fun toString(): String = "!" + (raw.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    public companion object {
        /** The local node (this radio). */
        public val LOCAL: NodeId = NodeId(0)

        /** Broadcast to all nodes on the mesh. */
        public val BROADCAST: NodeId = NodeId(0xFFFFFFFF.toInt())
    }
}

/**
 * Type-safe wrapper for channel indices.
 *
 * Valid range: `0..7` (the firmware exposes eight primary channel slots — channel 0 is the
 * primary/admin channel; 1..7 are secondary). Constructing with any other value throws
 * [IllegalArgumentException] from the value-class `init` block.
 *
 * **Validation note:** value-class `init` blocks only run from Kotlin call sites. JVM bytecode
 * that constructs [ChannelIndex] via reflection (or skips the boxing accessor) can bypass the
 * range check; this is a known limitation of Kotlin value classes and acceptable here because
 * the SDK never trusts caller-supplied indices for safety-critical decisions.
 *
 * **Java interop:** see [NodeId] — boxed accessors are exposed via
 * `-Xjvm-expose-boxed`, so Java callers use `getRaw()` and unmangled getters.
 *
 * @since 0.1.0
 */
@kotlin.jvm.JvmInline
public value class ChannelIndex(public val raw: Int) {
    init {
        require(raw in 0..MAX_CHANNEL_INDEX) {
            "ChannelIndex must be in 0..$MAX_CHANNEL_INDEX, was $raw"
        }
    }

    /** Friendly `"ch<n>"` label for logs. */
    override fun toString(): String = "ch$raw"

    public companion object {
        /** Largest valid channel index (inclusive). */
        public const val MAX_CHANNEL_INDEX: Int = 7
    }
}

/**
 * Type-safe wrapper for message/packet IDs (uint32).
 *
 * The wire format is uint32; any bit pattern in the backing `Int` is valid (the SDK
 * interprets the underlying value as unsigned). No range validation is performed.
 *
 * **Validation note:** see [NodeId] — value-class `init` blocks would only run from Kotlin,
 * but no validation is needed here since the full uint32 range is accepted.
 *
 * **Java interop:** see [NodeId] — boxed accessors are exposed via
 * `-Xjvm-expose-boxed`, so Java callers use e.g. `handle.getId()` rather than
 * the mangled `getId-eIGO-Ng()`.
 *
 * @since 0.1.0
 */
@kotlin.jvm.JvmInline
public value class MessageId(public val raw: Int) {
    /** Renders the underlying value as an unsigned decimal string for logs. */
    override fun toString(): String = (raw.toLong() and 0xFFFFFFFFL).toString()
}
