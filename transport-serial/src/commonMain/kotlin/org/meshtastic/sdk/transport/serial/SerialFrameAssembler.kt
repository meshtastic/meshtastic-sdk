/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.WireFraming

/**
 * Re-sync state machine for the Meshtastic serial wire framing
 * (`protocol.md §2`: START1=0x94, START2=0xC3, big-endian uint16 length).
 *
 * Pure Kotlin, no I/O: feed it bytes as they arrive and it calls [onFrame] with each
 * completed [Frame], header included, ready for `RadioTransport.frames()`. A payload
 * length over `WireFraming.MAX_PAYLOAD_SIZE` drops the header and resumes scanning, so a
 * stream resynchronizes after garbage without the caller's help. Not thread-safe: feed it
 * from one reader.
 *
 * Public so a host that owns its own byte stream, such as a custom USB or Bluetooth SPP
 * bridge, can build a [org.meshtastic.sdk.RadioTransport] on the same framing the bundled
 * serial transports use.
 *
 * @param onFrame called synchronously from [feed] for every completed frame
 * @since 0.3.0
 */
public class SerialFrameAssembler(private val onFrame: (Frame) -> Unit) {

    private enum class State { SCAN_FOR_START1, EXPECT_START2, READ_LEN_HI, READ_LEN_LO, READ_PAYLOAD }

    private var state = State.SCAN_FOR_START1
    private var lenHi = 0
    private var payloadLen = 0
    private var bytesRemaining = 0
    private val payloadBuf = Buffer()

    /** Feed one byte; emits through `onFrame` when it completes a frame. */
    public fun feed(byte: Byte) {
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
                        onFrame(Frame(byteArrayOf(START1, START2, 0, 0).toByteString()))
                        state = State.SCAN_FOR_START1
                    }

                    else -> {
                        bytesRemaining = payloadLen
                        state = State.READ_PAYLOAD
                    }
                }
            }

            State.READ_PAYLOAD -> {
                payloadBuf.writeByte(byte.toInt())
                if (--bytesRemaining == 0) {
                    val payload = payloadBuf.readByteArray()
                    payloadBuf.clear()
                    val header = byteArrayOf(
                        START1,
                        START2,
                        (payloadLen shr 8).toByte(),
                        (payloadLen and 0xFF).toByte(),
                    )
                    onFrame(Frame((header + payload).toByteString()))
                    state = State.SCAN_FOR_START1
                }
            }
        }
    }

    /** Feed the first [length] bytes of [bytes], a read buffer straight from the port. */
    public fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) feed(bytes[i])
    }

    internal companion object {
        const val MAX_PAYLOAD_SIZE = WireFraming.MAX_PAYLOAD_SIZE
        val START1: Byte = WireFraming.MAGIC_0
        val START2: Byte = WireFraming.MAGIC_1
    }
}
