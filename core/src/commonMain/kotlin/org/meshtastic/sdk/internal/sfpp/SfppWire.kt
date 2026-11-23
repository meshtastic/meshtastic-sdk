/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoReader
import okio.Buffer

/**
 * The replay fields of a `StoreForwardPlusPlus` message, read from its raw bytes.
 *
 * The pinned protobufs predate the replay types (`ITEM_FRAME` onward) and fields 17 to 20, so Wire
 * would drop them into `unknownFields` and report the type as `CANON_ANNOUNCE`. Tag numbers follow
 * firmware `mesh.pb.h` on the SF++ replay branch; this reader goes once the pin carries them.
 */
internal class SfppFrame(
    val type: Int,
    val messageHash: ByteArray,
    val message: ByteArray,
    val encapsulatedId: Int,
    val encapsulatedTo: Int,
    val encapsulatedFrom: Int,
    val heardAgoS: Long,
    val frame: Int,
    val originVerified: Boolean,
    val requestId: Int,
)

internal object SfppWire {
    /** Message types up to [LAST_CHAIN_TYPE] are the chain's, which the pinned Wire types decode. */
    const val LAST_CHAIN_TYPE: Int = 6
    const val ITEM_FRAME: Int = 13
    const val CLIENT_REQUEST: Int = 14
    const val REQUEST_DONE: Int = 15

    private const val TAG_TYPE = 1
    private const val TAG_MESSAGE_HASH = 2
    private const val TAG_MESSAGE = 5
    private const val TAG_ENCAPSULATED_ID = 6
    private const val TAG_ENCAPSULATED_TO = 7
    private const val TAG_ENCAPSULATED_FROM = 8
    private const val TAG_HEARD_AGO_S = 17
    private const val TAG_FRAME = 18
    private const val TAG_ORIGIN_VERIFIED = 19
    private const val TAG_REQUEST_ID = 20

    /** Decodes [payload], or returns null when it is not a well-formed protobuf message. */
    fun decode(payload: ByteArray): SfppFrame? = try {
        read(payload)
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        null
    }

    private fun read(payload: ByteArray): SfppFrame {
        var type = 0
        var hash = ByteArray(0)
        var message = ByteArray(0)
        var id = 0
        var to = 0
        var from = 0
        var heardAgo = 0L
        var frame = 0
        var verified = false
        var requestId = 0
        val reader = ProtoReader(Buffer().write(payload))
        reader.forEachTag { tag ->
            when (reader.peekFieldEncoding()) {
                FieldEncoding.VARINT -> {
                    val v = reader.readVarint64()
                    when (tag) {
                        TAG_TYPE -> type = v.toInt()
                        TAG_ENCAPSULATED_ID -> id = v.toInt()
                        TAG_ENCAPSULATED_TO -> to = v.toInt()
                        TAG_ENCAPSULATED_FROM -> from = v.toInt()
                        TAG_HEARD_AGO_S -> heardAgo = v and 0xFFFFFFFFL
                        TAG_FRAME -> frame = v.toInt()
                        TAG_ORIGIN_VERIFIED -> verified = v != 0L
                        TAG_REQUEST_ID -> requestId = v.toInt()
                    }
                }

                FieldEncoding.LENGTH_DELIMITED -> {
                    val b = reader.readBytes().toByteArray()
                    when (tag) {
                        TAG_MESSAGE_HASH -> hash = b
                        TAG_MESSAGE -> message = b
                    }
                }

                else -> reader.skip()
            }
        }
        return SfppFrame(type, hash, message, id, to, from, heardAgo, frame, verified, requestId)
    }
}
