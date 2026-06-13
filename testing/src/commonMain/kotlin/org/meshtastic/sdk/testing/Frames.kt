/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.testing

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.FromRadio
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.WireFraming

/**
 * Encode a device-side [FromRadio] envelope into a wire [Frame] (4-byte STREAM_API header +
 * protobuf payload), as a real transport would deliver it.
 *
 * The single source of truth for the framing tests need when driving an engine through a fake
 * transport — use with [FakeRadioTransport.injectFrame] for `FromRadio` variants that have no
 * dedicated `inject*` helper.
 *
 * @since 0.2.0
 */
public fun FromRadio.toFrame(): Frame {
    val proto = FromRadio.ADAPTER.encode(this)
    val bytes = ByteArray(WireFraming.HEADER_SIZE + proto.size).apply {
        this[0] = WireFraming.MAGIC_0
        this[1] = WireFraming.MAGIC_1
        this[2] = (proto.size shr 8).toByte()
        this[3] = (proto.size and 0xFF).toByte()
        proto.copyInto(this, destinationOffset = WireFraming.HEADER_SIZE)
    }
    return Frame(bytes.toByteString())
}
