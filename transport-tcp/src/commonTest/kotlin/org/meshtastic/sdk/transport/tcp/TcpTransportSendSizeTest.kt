/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.WireFraming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TcpTransportSendSizeTest {

    /**
     * TX-1 regression: a 512-byte payload yields a 516-byte on-wire envelope.
     * The transport must not reject this — only frames whose envelope exceeds
     * [WireFraming.MAX_FRAME_ON_WIRE] should fail the require().
     *
     * The transport isn't connected, so we expect to reach the
     * "TCP not connected" failure path *after* the size check passes.
     */
    @Test
    fun maxPayloadFrameIsAcceptedBySizeGuard() = runTest {
        val transport = TcpTransport("127.0.0.1", 4403)
        val maxEnvelope = ByteArray(WireFraming.MAX_FRAME_ON_WIRE) // 516 B
        val ex = assertFailsWith<MeshtasticException.Transport> {
            transport.send(Frame(maxEnvelope.toByteString()))
        }
        // Size check must pass; we should reach the "not connected" branch.
        assertEquals("TCP not connected", ex.message)
    }

    @Test
    fun oversizedFrameIsRejectedBySizeGuard() = runTest {
        val transport = TcpTransport("127.0.0.1", 4403)
        val tooBig = ByteArray(WireFraming.MAX_FRAME_ON_WIRE + 1)
        assertFailsWith<IllegalArgumentException> {
            transport.send(Frame(tooBig.toByteString()))
        }
    }
}
