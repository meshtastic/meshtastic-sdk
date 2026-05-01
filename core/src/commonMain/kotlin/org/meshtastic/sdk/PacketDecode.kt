/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
/**
 * Decode this packet's `decoded.payload` using the supplied Wire [adapter].
 *
 * Returns `null` when:
 * - `decoded` is null (payload was never set, or the packet is `encrypted` only),
 * - `decoded.payload` is empty, or
 * - the wire bytes fail to parse against [adapter] (any [Throwable] is swallowed and
 *   surfaced as `null` so callers can branch without a `try/catch`).
 *
 * **No portnum check** — callers asserting a payload type are responsible for verifying
 * `decoded.portnum` first if mismatch matters. The convenience overloads on this file
 * (e.g. [decodeAsPosition]) bake in the matching portnum guard.
 *
 * **Why not reified?** Wire's Kotlin runtime exposes one `ADAPTER` per generated message
 * class, but does not expose a generic way to recover that adapter from a reified type
 * parameter at the common-Kotlin level (no `kotlin.reflect` for proto types in commonMain,
 * and `Message.Companion.ADAPTER` is per-class, not on a shared base companion). Passing
 * the adapter explicitly is therefore the portable form; convenience overloads below cover
 * the common payload types.
 *
 * Example:
 * ```kotlin
 * val pos = packet.decodeAs(Position.ADAPTER)
 * ```
 *
 * @since 0.1.0
 */
public fun <T : Message<T, *>> MeshPacket.decodeAs(adapter: ProtoAdapter<T>): T? {
    val payload = decoded?.payload ?: return null
    if (payload.size == 0) return null
    return runCatching { adapter.decode(payload.toByteArray()) }.getOrNull()
}

private fun <T : Message<T, *>> MeshPacket.decodeIfPortnum(expected: PortNum, adapter: ProtoAdapter<T>): T? =
    if (decoded?.portnum == expected) decodeAs(adapter) else null

/** Decode the payload as UTF-8 text iff `decoded.portnum == TEXT_MESSAGE_APP`. */
public fun MeshPacket.decodeAsText(): String? = if (decoded?.portnum == PortNum.TEXT_MESSAGE_APP) {
    decoded?.payload?.utf8()
} else {
    null
}

/** Decode the payload as a [Position] iff `decoded.portnum == POSITION_APP`. */
public fun MeshPacket.decodeAsPosition(): Position? = decodeIfPortnum(PortNum.POSITION_APP, Position.ADAPTER)

/** Decode the payload as a [User] (NodeInfo user record) iff `decoded.portnum == NODEINFO_APP`. */
public fun MeshPacket.decodeAsUser(): User? = decodeIfPortnum(PortNum.NODEINFO_APP, User.ADAPTER)

/** Decode the payload as a full [NodeInfo] iff `decoded.portnum == NODEINFO_APP`. */
public fun MeshPacket.decodeAsNodeInfo(): NodeInfo? = decodeIfPortnum(PortNum.NODEINFO_APP, NodeInfo.ADAPTER)

/** Decode the payload as [Telemetry] iff `decoded.portnum == TELEMETRY_APP`. */
public fun MeshPacket.decodeAsTelemetry(): Telemetry? = decodeIfPortnum(PortNum.TELEMETRY_APP, Telemetry.ADAPTER)

/** Decode the payload as [Routing] iff `decoded.portnum == ROUTING_APP`. */
public fun MeshPacket.decodeAsRouting(): Routing? = decodeIfPortnum(PortNum.ROUTING_APP, Routing.ADAPTER)

/** Decode the payload as [AdminMessage] iff `decoded.portnum == ADMIN_APP`. */
public fun MeshPacket.decodeAsAdmin(): AdminMessage? = decodeIfPortnum(PortNum.ADMIN_APP, AdminMessage.ADAPTER)
