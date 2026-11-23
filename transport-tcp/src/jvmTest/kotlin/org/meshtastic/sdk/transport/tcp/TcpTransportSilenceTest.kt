/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class TcpTransportSilenceTest {
    // Firmware before 2.7.4 sends nothing back for a heartbeat (PhoneAPI.cpp, #7506), so a healthy link to a radio
    // on a quiet mesh stays silent for minutes; only the engine's watchdog, armed by a heartbeat reply, may end it.
    @Test
    fun aSilentPeerIsNotDroppedByTheTransport() = runTest {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3_000
            val transport = TcpTransport("127.0.0.1", listener.localPort)
            try {
                transport.connect()
                listener.accept().use { _ ->
                    val reading = backgroundScope.async { runCatching { transport.frames().collect() } }
                    runCurrent()
                    advanceTimeBy(10 * 60_000L)
                    runCurrent()
                    val ended = if (reading.isCompleted) reading.getCompleted().exceptionOrNull() else null
                    assertFalse(reading.isCompleted, "the transport ended a silent link: $ended")
                }
            } finally {
                transport.disconnect()
            }
        }
    }
}
