/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ToRadio
/**
 * Decode a [MeshPacket] from raw wire bytes. Returns `null` if [this] is not a valid
 * `MeshPacket` proto encoding (any decoding [Throwable] is swallowed).
 *
 * Useful when persisting captured packets in arbitrary byte storage and rehydrating
 * later, or when bridging to a non-SDK transport that hands you bytes directly.
 *
 * Example:
 * ```kotlin
 * val bytes: ByteArray = readFromCapture()
 * val packet = bytes.toMeshPacket() ?: error("not a MeshPacket")
 * ```
 *
 * @since 0.1.0
 */
public fun ByteArray.toMeshPacket(): MeshPacket? = runCatching { MeshPacket.ADAPTER.decode(this) }.getOrNull()

/**
 * Encode this [MeshPacket] to its wire byte representation.
 *
 * @since 0.1.0
 */
public fun MeshPacket.toByteArray(): ByteArray = MeshPacket.ADAPTER.encode(this)

/**
 * Decode a [FromRadio] envelope from raw wire bytes. Returns `null` on parse failure.
 *
 * @since 0.1.0
 */
public fun ByteArray.toFromRadio(): FromRadio? = runCatching { FromRadio.ADAPTER.decode(this) }.getOrNull()

/**
 * Encode this [FromRadio] envelope to its wire byte representation.
 *
 * @since 0.1.0
 */
public fun FromRadio.toByteArray(): ByteArray = FromRadio.ADAPTER.encode(this)

/**
 * Decode a [ToRadio] envelope from raw wire bytes. Returns `null` on parse failure.
 *
 * @since 0.1.0
 */
public fun ByteArray.toToRadio(): ToRadio? = runCatching { ToRadio.ADAPTER.decode(this) }.getOrNull()

/**
 * Encode this [ToRadio] envelope to its wire byte representation.
 *
 * @since 0.1.0
 */
public fun ToRadio.toByteArray(): ByteArray = ToRadio.ADAPTER.encode(this)
