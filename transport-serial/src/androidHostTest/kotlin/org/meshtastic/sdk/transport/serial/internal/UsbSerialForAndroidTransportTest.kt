/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsbSerialForAndroidTransportTest {

    @Test
    fun connectConfiguresPortAssertsControlLinesAndWakesTheRadio(): Unit = runBlocking {
        val port = FakePort()
        val transport = transport(port)

        transport.connect()

        assertEquals(
            listOf(115_200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE),
            port.parameters,
        )
        assertTrue(port.dtr && port.rts)
        assertContentEquals(
            byteArrayOf(0x94.toByte(), 0x94.toByte(), 0x94.toByte(), 0x94.toByte()),
            port.writes.single(),
        )
        assertEquals(TransportState.Connected, transport.state.value)
        transport.disconnect()
        assertTrue(port.closed)
    }

    @Test
    fun bytesReadFromThePortArriveAsFrames(): Unit = runBlocking {
        val port = FakePort()
        val transport = transport(port)
        transport.connect()
        val a = frame(byteArrayOf(1, 2, 3))
        val b = frame(byteArrayOf(4))
        // Split across reads so the assembler, not the read size, decides frame boundaries.
        port.reads.add(a.copyOfRange(0, 5))
        port.reads.add(a.copyOfRange(5, a.size) + b)

        val frames = withTimeout(5_000) { transport.frames().take(2).toList() }

        assertContentEquals(a, frames[0].bytes.toByteArray())
        assertContentEquals(b, frames[1].bytes.toByteArray())
        transport.disconnect()
    }

    @Test
    fun sendWritesTheFrameBytes(): Unit = runBlocking {
        val port = FakePort()
        val transport = transport(port)
        transport.connect()
        val bytes = frame(byteArrayOf(9, 9))

        transport.send(Frame(bytes.toByteString()))

        assertContentEquals(bytes, port.writes.last())
        transport.disconnect()
    }

    @Test
    fun oversizedFrameIsRejectedBeforeTouchingThePort(): Unit = runBlocking {
        val port = FakePort()
        val transport = transport(port)
        transport.connect()

        assertFailsWith<IllegalArgumentException> {
            transport.send(Frame(ByteArray(WireFraming.MAX_FRAME_ON_WIRE + 1).toByteString()))
        }
        assertEquals(1, port.writes.size)
        transport.disconnect()
    }

    @Test
    fun readFailureAfterConnectIsRecoverableAndEndsFrames(): Unit = runBlocking {
        val port = FakePort()
        val transport = transport(port)
        transport.connect()

        port.failNextRead = IOException("device detached")
        val frames = withTimeout(5_000) { transport.frames().toList() }
        val error = withTimeout(5_000) { transport.state.first { it is TransportState.Error } }

        assertTrue(frames.isEmpty())
        assertTrue(assertIs<TransportState.Error>(error).recoverable)
        transport.disconnect()
    }

    @Test
    fun openFailureSurfacesAsTransportException(): Unit = runBlocking {
        val transport = UsbSerialForAndroidTransport(
            openPort = { throw IOException("permission") },
            identity = TransportIdentity("serial-android:test"),
            baudRate = 115_200,
        )

        assertFailsWith<MeshtasticException.Transport> { transport.connect() }
        assertIs<TransportState.Error>(transport.state.value)
    }

    private fun transport(port: FakePort) = UsbSerialForAndroidTransport(
        openPort = { port },
        identity = TransportIdentity("serial-android:test"),
        baudRate = 115_200,
    )

    private fun frame(payload: ByteArray): ByteArray =
        byteArrayOf(0x94.toByte(), 0xC3.toByte(), (payload.size shr 8).toByte(), (payload.size and 0xFF).toByte()) +
            payload

    /** Already-open port: `open()` is the opener's job, which the transport never calls itself. */
    private class FakePort : UsbSerialPort {
        val reads = LinkedBlockingQueue<ByteArray>()
        val writes = mutableListOf<ByteArray>()
        val parameters = mutableListOf<Int>()
        var dtr = false
        var rts = false
        var closed = false

        @Volatile
        var failNextRead: IOException? = null

        override fun read(dest: ByteArray, timeout: Int): Int {
            failNextRead?.let {
                failNextRead = null
                throw it
            }
            val chunk = reads.poll(timeout.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            chunk.copyInto(dest)
            return chunk.size
        }

        override fun read(dest: ByteArray, length: Int, timeout: Int): Int = read(dest, timeout)

        override fun write(src: ByteArray, timeout: Int) {
            writes += src.copyOf()
        }

        override fun write(src: ByteArray, length: Int, timeout: Int) = write(src.copyOf(length), timeout)

        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
            parameters.clear()
            parameters += listOf(baudRate, dataBits, stopBits, parity)
        }

        override fun setDTR(value: Boolean) {
            dtr = value
        }

        override fun setRTS(value: Boolean) {
            rts = value
        }

        override fun close() {
            closed = true
        }

        override fun isOpen(): Boolean = !closed
        override fun open(connection: UsbDeviceConnection?) = error("the opener owns open()")
        override fun getDriver(): UsbSerialDriver? = null
        override fun getDevice(): UsbDevice? = null
        override fun getPortNumber(): Int = 0
        override fun getWriteEndpoint(): UsbEndpoint? = null
        override fun getReadEndpoint(): UsbEndpoint? = null
        override fun getSerial(): String? = null
        override fun setReadQueue(bufferCount: Int, bufferSize: Int) = Unit
        override fun getReadQueueBufferCount(): Int = 0
        override fun getReadQueueBufferSize(): Int = 0
        override fun getCD(): Boolean = false
        override fun getCTS(): Boolean = false
        override fun getDSR(): Boolean = false
        override fun getDTR(): Boolean = dtr
        override fun getRI(): Boolean = false
        override fun getRTS(): Boolean = rts
        override fun getControlLines(): EnumSet<UsbSerialPort.ControlLine> =
            EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
        override fun getSupportedControlLines(): EnumSet<UsbSerialPort.ControlLine> =
            EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
        override fun setFlowControl(flowControl: UsbSerialPort.FlowControl?) = Unit
        override fun getFlowControl(): UsbSerialPort.FlowControl = UsbSerialPort.FlowControl.NONE
        override fun getSupportedFlowControl(): EnumSet<UsbSerialPort.FlowControl> =
            EnumSet.of(UsbSerialPort.FlowControl.NONE)
        override fun getXON(): Boolean = false
        override fun purgeHwBuffers(purgeWriteBuffers: Boolean, purgeReadBuffers: Boolean) = Unit
        override fun setBreak(value: Boolean) = Unit
    }
}
