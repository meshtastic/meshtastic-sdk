/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import org.meshtastic.sdk.Frame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SerialFrameAssemblerTest {

    private fun frame(payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf(0x94.toByte(), 0xC3.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
    }

    @Test
    fun assemblesSingleFrame() {
        val out = mutableListOf<Frame>()
        val a = SerialFrameAssembler { out += it }
        a.feed(frame(byteArrayOf(1, 2, 3, 4)))
        assertEquals(1, out.size)
        assertContentEquals(frame(byteArrayOf(1, 2, 3, 4)), out.single().bytes.toByteArray())
    }

    @Test
    fun resyncsAfterGarbage() {
        val out = mutableListOf<Frame>()
        val a = SerialFrameAssembler { out += it }
        a.feed(byteArrayOf(0x00, 0xFF.toByte(), 0x42, 0x94.toByte(), 0x00))
        a.feed(frame(byteArrayOf(7, 8)))
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(7, 8), out.single().bytes.toByteArray().drop(4).toByteArray())
    }

    @Test
    fun emitsZeroLengthFrame() {
        val out = mutableListOf<Frame>()
        val a = SerialFrameAssembler { out += it }
        a.feed(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x00, 0x00))
        assertEquals(1, out.size)
        assertEquals(4, out.single().bytes.size)
    }

    @Test
    fun handlesByteAtATime() {
        val out = mutableListOf<Frame>()
        val a = SerialFrameAssembler { out += it }
        for (b in frame(byteArrayOf(9, 9, 9))) a.feed(b)
        assertEquals(1, out.size)
    }

    @Test
    fun discardsOversizeFrameAndResyncs() {
        val out = mutableListOf<Frame>()
        val a = SerialFrameAssembler { out += it }
        // Length 0xFFFF > MAX_PAYLOAD_SIZE; FSM should drop and resync.
        a.feed(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        a.feed(frame(byteArrayOf(1)))
        assertEquals(1, out.size)
    }
}
