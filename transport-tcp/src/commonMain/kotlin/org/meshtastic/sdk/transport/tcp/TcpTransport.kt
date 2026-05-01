/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming

/** TCP transport for the Meshtastic PhoneAPI. */
public class TcpTransport(private val host: String, private val port: Int = 4403) : RadioTransport {

    override val identity: TransportIdentity = TransportIdentity.of(TransportSpec.Tcp(host, port))

    private val _state: MutableStateFlow<TransportState> =
        MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var selectorManager: SelectorManager? = null
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null

    override suspend fun connect() {
        _state.value = TransportState.Connecting
        try {
            val sm = SelectorManager(Dispatchers.Default)
            selectorManager = sm
            // SO_KEEPALIVE for half-open NAT; noDelay disables Nagle for interactive traffic.
            val s = aSocket(sm).tcp().connect(host, port) {
                keepAlive = true
                noDelay = true
            }
            socket = s
            readChannel = s.openReadChannel()
            val wc = s.openWriteChannel(autoFlush = false)
            writeChannel = wc

            // 4× START1 wake bytes nudge the firmware's framer to a clean state.
            wc.writeFully(WAKE_BYTES)
            wc.flush()

            _state.value = TransportState.Connected
        } catch (e: Exception) {
            _state.value = TransportState.Error(e, recoverable = true)
            runCatching { socket?.close() }
            runCatching { selectorManager?.close() }
            socket = null
            readChannel = null
            writeChannel = null
            selectorManager = null
            throw MeshtasticException.Transport("TCP connect to $host:$port failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        withContext(NonCancellable) {
            _state.value = TransportState.Disconnected
            runCatching { socket?.close() }
            runCatching { selectorManager?.close() }
            socket = null
            readChannel = null
            writeChannel = null
            selectorManager = null
        }
    }

    override suspend fun send(frame: Frame) {
        require(frame.bytes.size <= WireFraming.MAX_FRAME_ON_WIRE) {
            "TCP frame ${frame.bytes.size} B exceeds MAX_FRAME_ON_WIRE=${WireFraming.MAX_FRAME_ON_WIRE} B"
        }
        val ch = writeChannel ?: throw MeshtasticException.Transport("TCP not connected")
        try {
            ch.writeFully(frame.bytes.toByteArray())
            ch.flush()
        } catch (e: Exception) {
            throw MeshtasticException.Transport("TCP send failed: ${e.message}", e)
        }
    }

    /**
     * Cold flow that reads frames from the TCP socket.
     */
    override fun frames(): Flow<Frame> = flow {
        val ch = readChannel ?: throw MeshtasticException.Transport("TCP not connected")

        var fsmState = FsmState.SCAN_FOR_START1
        var lenHi = 0
        var payloadLen = 0
        val payloadBuf = Buffer()
        var bytesRemaining = 0
        val readBuf = ByteArray(READ_CHUNK_BYTES)

        try {
            while (!ch.isClosedForRead) {
                val n = withTimeoutOrNull(READ_TIMEOUT_MS) {
                    ch.readAvailable(readBuf, 0, readBuf.size)
                } ?: throw MeshtasticException.Transport("TCP read timeout after ${READ_TIMEOUT_MS}ms")
                if (n < 0) break
                if (n == 0) continue

                for (i in 0 until n) {
                    val b = readBuf[i]
                    when (fsmState) {
                        FsmState.SCAN_FOR_START1 -> {
                            if (b == START1) fsmState = FsmState.EXPECT_START2
                        }

                        FsmState.EXPECT_START2 -> {
                            fsmState = when (b) {
                                START2 -> FsmState.READ_LEN_HI
                                START1 -> FsmState.EXPECT_START2
                                else -> FsmState.SCAN_FOR_START1
                            }
                        }

                        FsmState.READ_LEN_HI -> {
                            lenHi = b.toInt() and 0xFF
                            fsmState = FsmState.READ_LEN_LO
                        }

                        FsmState.READ_LEN_LO -> {
                            val lenLo = b.toInt() and 0xFF
                            payloadLen = (lenHi shl 8) or lenLo
                            when {
                                payloadLen > WireFraming.MAX_PAYLOAD_SIZE -> {
                                    payloadBuf.clear()
                                    fsmState = FsmState.SCAN_FOR_START1
                                }

                                payloadLen == 0 -> {
                                    val header = byteArrayOf(START1, START2, 0, 0)
                                    emit(Frame(ByteString(header)))
                                    fsmState = FsmState.SCAN_FOR_START1
                                }

                                else -> {
                                    bytesRemaining = payloadLen
                                    fsmState = FsmState.READ_PAYLOAD
                                }
                            }
                        }

                        FsmState.READ_PAYLOAD -> {
                            payloadBuf.writeByte(b)
                            if (--bytesRemaining == 0) {
                                val payload = payloadBuf.readByteArray()
                                payloadBuf.clear()
                                fsmState = FsmState.SCAN_FOR_START1

                                val header = byteArrayOf(
                                    START1,
                                    START2,
                                    (payloadLen shr 8).toByte(),
                                    (payloadLen and 0xFF).toByte(),
                                )
                                emit(Frame(ByteString(header + payload)))
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    private enum class FsmState {
        SCAN_FOR_START1,
        EXPECT_START2,
        READ_LEN_HI,
        READ_LEN_LO,
        READ_PAYLOAD,
    }

    private companion object {
        const val READ_TIMEOUT_MS = 65_000L

        const val READ_CHUNK_BYTES = 256

        val START1: Byte = WireFraming.MAGIC_0
        val START2: Byte = WireFraming.MAGIC_1
        val WAKE_BYTES = byteArrayOf(START1, START1, START1, START1)
    }
}
