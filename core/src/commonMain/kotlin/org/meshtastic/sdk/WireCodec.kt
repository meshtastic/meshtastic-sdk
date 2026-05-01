/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio

/**
 * Pure codec for wire framing and protobuf serialization.
 *
 * Handles framing per protocol.md §2 (TCP/Serial stream framing with resync) and encodes/decodes
 * protobuf envelopes. No I/O, no state machines — just encode/decode.
 *
 * **Framing format** (TCP & Serial transports):
 * ```
 * +------+------+----------+----------+==================+
 * | 0x94 | 0xC3 |   LEN_HI |   LEN_LO |  protobuf bytes  |
 * +------+------+----------+----------+==================+
 * ```
 *
 * - Sync bytes: `0x94 0xC3`
 * - Length: big-endian uint16 (payload only, excludes 4-byte header)
 * - Max payload: 512 bytes
 *
 * @since 0.1.0
 */
public object WireCodec {
    private const val START1: Byte = 0x94.toByte()
    private const val START2: Byte = 0xC3.toByte()
    private const val MAX_FRAME_SIZE: Int = 512

    /**
     * Encode a `ToRadio` into a framed byte array ready for transmission.
     *
     * This is a **wire-layer** check — it validates the total serialized envelope size, not the
     * application payload. Application-layer payload size (max [DATA_PAYLOAD_LEN] bytes)
     * must be enforced by callers before building the `ToRadio` envelope.
     *
     * @param message the outbound envelope
     * @return the framed payload (includes 4-byte header)
     * @throws MeshtasticException.Protocol if the serialized envelope exceeds [MAX_FRAME_SIZE] bytes,
     *   or if serialization fails. Envelope-overflow is a **protocol-layer** error (the caller
     *   constructed a malformed wire frame, which is distinct from exceeding the application
     *   payload limit — that's [MeshtasticException.PayloadTooLarge], enforced at
     *   [RadioClient.send]).
     */
    public fun encodeToRadio(message: ToRadio): ByteArray {
        val payload = try {
            ToRadio.ADAPTER.encode(message)
        } catch (e: Exception) {
            throw MeshtasticException.Protocol("Failed to encode ToRadio: ${e.message}")
        }

        if (payload.size > MAX_FRAME_SIZE) {
            throw MeshtasticException.Protocol(
                "ToRadio envelope too large for wire: ${payload.size} bytes (max $MAX_FRAME_SIZE)",
            )
        }

        return ByteArray(4 + payload.size).apply {
            this[0] = START1
            this[1] = START2
            this[2] = (payload.size shr 8).toByte()
            this[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(this, destinationOffset = 4)
        }
    }

    /**
     * Decode a framed protobuf into a `FromRadio` message.
     *
     * Caller is responsible for extracting the payload (4-byte header + N bytes of protobuf).
     *
     * @param payload the raw protobuf bytes (without framing header)
     * @return the decoded envelope
     * @throws MeshtasticException.Protocol if deserialization fails
     */
    public fun decodeFromRadio(payload: ByteArray): FromRadio = try {
        FromRadio.ADAPTER.decode(payload)
    } catch (e: Exception) {
        throw MeshtasticException.Protocol("Failed to decode FromRadio: ${e.message}")
    }

    /**
     * Streaming decoder state machine for TCP/Serial transports.
     *
     * Handles the resync algorithm per protocol.md §2 to recover from garbage on the wire.
     * Thread-safe by virtue of being stateless; each decoder owns its state.
     */
    public class FrameDecoder {
        private enum class State {
            SCAN_FOR_START1,
            EXPECT_START2,
            READ_LEN_HI,
            READ_LEN_LO,
            READ_PAYLOAD,
        }

        private var state = State.SCAN_FOR_START1
        private var lenHi = 0
        private var payloadLen = 0
        private val buffer = Buffer()
        private var bytesRemaining = 0

        /**
         * Feed a single byte into the decoder.
         *
         * @param b the byte
         * @return a complete `FromRadio` if one is ready to emit, null otherwise
         * @throws MeshtasticException.Protocol if a malformed frame is detected
         */
        public fun feed(b: Byte): FromRadio? {
            when (state) {
                State.SCAN_FOR_START1 -> {
                    if (b == START1) {
                        state = State.EXPECT_START2
                    }
                }

                State.EXPECT_START2 -> {
                    when (b) {
                        START2 -> state = State.READ_LEN_HI

                        START1 -> {
                            // Double 0x94 is valid
                            state = State.EXPECT_START2
                        }

                        else -> state = State.SCAN_FOR_START1
                    }
                }

                State.READ_LEN_HI -> {
                    lenHi = b.toInt() and 0xFF
                    state = State.READ_LEN_LO
                }

                State.READ_LEN_LO -> {
                    val lenLo = b.toInt() and 0xFF
                    payloadLen = (lenHi shl 8) or lenLo

                    if (payloadLen > MAX_FRAME_SIZE) {
                        // Bogus length, resync
                        state = State.SCAN_FOR_START1
                        buffer.clear()
                    } else if (payloadLen == 0) {
                        // Zero-length frame: emit immediately without entering READ_PAYLOAD.
                        // Transitioning to READ_PAYLOAD with bytesRemaining=0 would consume
                        // the first byte of the next frame before emitting, corrupting the stream.
                        state = State.SCAN_FOR_START1
                        return try {
                            decodeFromRadio(ByteArray(0))
                        } catch (e: MeshtasticException) {
                            null
                        }
                    } else {
                        bytesRemaining = payloadLen
                        state = State.READ_PAYLOAD
                    }
                }

                State.READ_PAYLOAD -> {
                    buffer.writeByte(b)
                    bytesRemaining--

                    if (bytesRemaining == 0) {
                        val payload = buffer.readByteArray()
                        buffer.clear()
                        state = State.SCAN_FOR_START1

                        return try {
                            decodeFromRadio(payload)
                        } catch (e: MeshtasticException) {
                            // Log warning but don't propagate; continue scanning
                            null
                        }
                    }
                }
            }

            return null
        }

        /**
         * Feed a byte array, yielding all complete frames.
         *
         * @param bytes the raw bytes to process
         * @return a list of decoded `FromRadio` messages
         */
        public fun feedBytes(bytes: ByteArray): List<FromRadio> {
            val result = mutableListOf<FromRadio>()
            for (b in bytes) {
                feed(b)?.let { result.add(it) }
            }
            return result
        }

        /**
         * Reset the decoder to initial state (e.g., on disconnect).
         */
        public fun reset() {
            state = State.SCAN_FOR_START1
            buffer.clear()
        }
    }
}
