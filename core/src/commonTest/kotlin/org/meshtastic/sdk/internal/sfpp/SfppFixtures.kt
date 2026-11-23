/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoWriter
import okio.Buffer
import okio.ByteString.Companion.toByteString

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/**
 * One channel text and its encryptions, computed with Python `cryptography` (AES-CTR and
 * `AESCCM(tag_length=12)`) over the firmware nonce and AAD layout, not with the code under test.
 */
internal object SfppVector {
    const val FROM: Int = 0x1A2B3C4D
    const val ID: Int = 0x0BADBEEF
    const val TEXT: String = "hello mesh, recovered"

    /** `Data{portnum: TEXT_MESSAGE_APP, payload: TEXT}`. */
    val data: ByteArray = hex("0801121568656c6c6f206d6573682c207265636f7665726564")

    /** AES-128-CTR under the default key (`psk = [0x01]`). */
    val ctrDefault: ByteArray = hex("23e24f492dd5f5ccb3b557a0c1f0a70288d257033153e3bba1")

    /** AES-256-CTR under the SP 800-38A AES-256 key. */
    val ctr256: ByteArray = hex("713cc7eb1267aa02d4045f8498263c83f2791c0ea87f04fb78")
    val key256: ByteArray = hex("603DEB1015CA71BE2B73AEF0857D77811F352C073B6108D72D9810A30914DFF4")

    /** AES-128-CCM under the default key, to broadcast, 12-byte tag appended. */
    val ccmDefault: ByteArray = hex("e751bd92530091396a47ad3c03126216b88b4013ae5d721b5dd538295cd4da74f04e4f83fc")

    /** SHA-256(ctrDefault ‖ to=broadcast ‖ FROM ‖ ID), first 16 bytes, from Python `hashlib`. */
    val ctrDefaultHash: ByteArray = hex("d59158415c84d1485623cb8d4eeee772")

    /** The same text from `0xDEADBEEF` with id `0x80000001`: every uint32 with its high bit set. */
    const val HIGH_FROM: Int = 0xDEADBEEF.toInt()
    const val HIGH_ID: Int = 0x80000001.toInt()
    val highCtrDefault: ByteArray = hex("fa286cae8abe99a849566708a4bb7f97e92ae527e7211336e3")
    val highHash: ByteArray = hex("fdaa4ca3ea5fddd3413b5badbdf75199")
}

/** Encodes a `StoreForwardPlusPlus` with the replay fields, using the tag numbers firmware's `mesh.pb.h` declares. */
internal fun encodeSfpp(
    type: Int,
    hash: ByteArray = ByteArray(0),
    message: ByteArray = ByteArray(0),
    id: Int = 0,
    to: Int = 0,
    from: Int = 0,
    heardAgoS: Int = 0,
    frame: Int = 0,
    originVerified: Boolean = false,
): ByteArray {
    val buffer = Buffer()
    val w = ProtoWriter(buffer)
    fun varint(tag: Int, v: Int) {
        if (v == 0) return
        w.writeTag(tag, FieldEncoding.VARINT)
        // uint32 as nanopb writes it: unsigned, at most five bytes.
        w.writeVarint64(v.toLong() and 0xFFFFFFFFL)
    }
    fun bytes(tag: Int, b: ByteArray) {
        if (b.isEmpty()) return
        w.writeTag(tag, FieldEncoding.LENGTH_DELIMITED)
        w.writeVarint32(b.size)
        w.writeBytes(b.toByteString())
    }
    varint(1, type)
    bytes(2, hash)
    bytes(5, message)
    varint(6, id)
    varint(7, to)
    varint(8, from)
    varint(17, heardAgoS)
    varint(18, frame)
    varint(19, if (originVerified) 1 else 0)
    return buffer.readByteArray()
}

/** An `ITEM_FRAME` as the receiver sees it after decoding. */
internal fun itemFrame(
    hash: ByteArray,
    message: ByteArray,
    frame: Int = 0,
    envelope: Boolean = true,
    to: Int = 0,
    from: Int = SfppVector.FROM,
    id: Int = SfppVector.ID,
    heardAgoS: Int = 0,
    originVerified: Boolean = false,
): SfppFrame = SfppWire.decode(
    encodeSfpp(
        type = SfppWire.ITEM_FRAME,
        hash = hash,
        message = message,
        id = if (envelope) id else 0,
        to = if (envelope) to else 0,
        from = if (envelope) from else 0,
        heardAgoS = if (envelope) heardAgoS else 0,
        frame = frame,
        originVerified = envelope && originVerified,
    ),
)!!

/** Low byte index, next byte total, as firmware `packFrame` does. */
internal fun packFrame(index: Int, total: Int): Int = index or (total shl 8)
