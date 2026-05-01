/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.WireFraming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JSerialCommTransportSendSizeTest {

    private fun newTransport(): JSerialCommTransport? {
        // jSerialComm requires a real port name; pick the first enumerated port if
        // any. On dev hosts/CI without serial hardware we skip the test rather
        // than fail spuriously — the require() check we exercise here is pure
        // and platform-independent.
        val port = SerialPort.getCommPorts().firstOrNull() ?: return null
        val identity = TransportIdentity.of(TransportSpec.SerialJvm(port.systemPortName))
        return JSerialCommTransport(port, identity, baudRate = 115_200)
    }

    /**
     * TX-1 regression: a 512-byte payload yields a 516-byte on-wire envelope.
     * The transport must not reject this — only envelopes that exceed
     * [WireFraming.MAX_FRAME_ON_WIRE] should fail the require().
     */
    @Test
    fun maxPayloadFrameIsAcceptedBySizeGuard() = runTest {
        val transport = newTransport() ?: return@runTest
        val maxEnvelope = ByteArray(WireFraming.MAX_FRAME_ON_WIRE) // 516 B
        val ex = assertFailsWith<MeshtasticException.Transport> {
            transport.send(Frame(ByteString(maxEnvelope)))
        }
        // Size check must pass; we should reach the "not connected" branch.
        assertEquals("Serial not connected", ex.message)
    }

    @Test
    fun oversizedFrameIsRejectedBySizeGuard() = runTest {
        val transport = newTransport() ?: return@runTest
        val tooBig = ByteArray(WireFraming.MAX_FRAME_ON_WIRE + 1)
        assertFailsWith<IllegalArgumentException> {
            transport.send(Frame(ByteString(tooBig)))
        }
    }
}
