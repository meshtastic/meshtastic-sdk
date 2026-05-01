/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * Single source of truth for the Meshtastic STREAM_API wire-framing constants
 * shared by every stream-oriented transport (TCP, USB-CDC serial, BLE).
 *
 * Per `protocol.md §2`, the on-wire framing is:
 * ```
 * +------+------+----------+----------+==================+
 * | 0x94 | 0xC3 |   LEN_HI |   LEN_LO |  protobuf bytes  |
 * +------+------+----------+----------+==================+
 * ```
 *
 * The 4-byte header is followed by up to [MAX_PAYLOAD_SIZE] bytes of protobuf
 * payload, giving a total wire envelope of at most [MAX_FRAME_ON_WIRE] bytes.
 *
 * Centralising these here lets the per-transport framers avoid redefining the
 * same magic numbers; see `WireCodec` for the engine encoder/decoder that uses
 * the same values.
 */
public object WireFraming {
    /** First sync byte (`START1`) of every STREAM_API frame. */
    public const val MAGIC_0: Byte = 0x94.toByte()

    /** Second sync byte (`START2`). */
    public const val MAGIC_1: Byte = 0xC3.toByte()

    /** Fixed framing header length: `MAGIC_0 MAGIC_1 LEN_HI LEN_LO`. */
    public const val HEADER_SIZE: Int = 4

    /**
     * Maximum payload size in bytes (exclusive of header). Enforced by the
     * firmware's de-framer; frames whose announced length exceeds this trigger
     * a resync rather than a buffer overflow.
     */
    public const val MAX_PAYLOAD_SIZE: Int = 512

    /**
     * Maximum total on-wire envelope size: [HEADER_SIZE] + [MAX_PAYLOAD_SIZE].
     *
     * Transports MUST validate outbound frames against this value (the full
     * envelope including the 4-byte header), not against [MAX_PAYLOAD_SIZE]
     * alone — using the smaller bound rejects valid 509–512 B payloads.
     */
    public const val MAX_FRAME_ON_WIRE: Int = HEADER_SIZE + MAX_PAYLOAD_SIZE
}
