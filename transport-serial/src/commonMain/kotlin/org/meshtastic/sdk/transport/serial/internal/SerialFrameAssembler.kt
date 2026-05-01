/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.WireFraming

/**
 * Re-sync state machine for the Meshtastic serial wire framing
 * (`protocol.md §2`: START1=0x94, START2=0xC3, big-endian uint16 length).
 *
 * Pure Kotlin, no I/O — fed one byte at a time and emits each completed
 * [Frame] back through a callback. Identical state machine to TcpTransport;
 * factored here so jvm + android serial can share it.
 */
internal class SerialFrameAssembler(private val onFrame: (Frame) -> Unit) {

    private enum class State { SCAN_FOR_START1, EXPECT_START2, READ_LEN_HI, READ_LEN_LO, READ_PAYLOAD }

    private var state = State.SCAN_FOR_START1
    private var lenHi = 0
    private var payloadLen = 0
    private var bytesRemaining = 0
    private val payloadBuf = Buffer()

    fun feed(byte: Byte) {
        when (state) {
            State.SCAN_FOR_START1 -> if (byte == START1) state = State.EXPECT_START2

            State.EXPECT_START2 -> state = when (byte) {
                START2 -> State.READ_LEN_HI
                START1 -> State.EXPECT_START2
                else -> State.SCAN_FOR_START1
            }

            State.READ_LEN_HI -> {
                lenHi = byte.toInt() and 0xFF
                state = State.READ_LEN_LO
            }

            State.READ_LEN_LO -> {
                val lenLo = byte.toInt() and 0xFF
                payloadLen = (lenHi shl 8) or lenLo
                when {
                    payloadLen > MAX_PAYLOAD_SIZE -> {
                        payloadBuf.clear()
                        state = State.SCAN_FOR_START1
                    }

                    payloadLen == 0 -> {
                        onFrame(Frame(ByteString(byteArrayOf(START1, START2, 0, 0))))
                        state = State.SCAN_FOR_START1
                    }

                    else -> {
                        bytesRemaining = payloadLen
                        state = State.READ_PAYLOAD
                    }
                }
            }

            State.READ_PAYLOAD -> {
                payloadBuf.writeByte(byte)
                if (--bytesRemaining == 0) {
                    val payload = payloadBuf.readByteArray()
                    payloadBuf.clear()
                    val header = byteArrayOf(
                        START1,
                        START2,
                        (payloadLen shr 8).toByte(),
                        (payloadLen and 0xFF).toByte(),
                    )
                    onFrame(Frame(ByteString(header + payload)))
                    state = State.SCAN_FOR_START1
                }
            }
        }
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) feed(bytes[i])
    }

    internal companion object {
        const val MAX_PAYLOAD_SIZE = WireFraming.MAX_PAYLOAD_SIZE
        val START1: Byte = WireFraming.MAGIC_0
        val START2: Byte = WireFraming.MAGIC_1
    }
}
