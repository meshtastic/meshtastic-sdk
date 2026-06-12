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
import org.meshtastic.proto.MeshPacket
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
 * `decoded.portnum` first if mismatch matters. The typed accessors in `PayloadAccessors.kt`
 * (e.g. [asPosition], [asWaypoint]) bake in the matching portnum guard; this generic form is
 * the escape hatch for portnums without a typed accessor (Paxcount, StoreAndForward, …).
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
