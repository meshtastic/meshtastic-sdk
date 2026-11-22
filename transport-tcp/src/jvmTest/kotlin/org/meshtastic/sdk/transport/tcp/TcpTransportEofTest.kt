/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.meshtastic.sdk.MeshtasticException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TcpTransportEofTest {
    @Test
    fun cleanPeerEofReportsTransportFailure() = runTest {
        withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 3_000
                val transport = TcpTransport("127.0.0.1", listener.localPort)
                try {
                    transport.connect()
                    listener.accept().use { peer ->
                        peer.soTimeout = 3_000
                        assertEquals(4, peer.getInputStream().readNBytes(4).size)
                        peer.shutdownOutput()
                        val failure = assertFailsWith<MeshtasticException.Transport> {
                            withTimeout(3_000) { transport.frames().collect() }
                        }
                        assertEquals("TCP socket closed by peer", failure.message)
                    }
                } finally {
                    transport.disconnect()
                }
            }
        }
    }
}
