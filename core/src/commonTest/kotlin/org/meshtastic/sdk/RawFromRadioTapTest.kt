/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LogRecord
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [RadioClient.fromRadio] carries every decoded envelope, including ones [RadioClient.packets] never sees. */
@OptIn(ExperimentalCoroutinesApi::class)
class RawFromRadioTapTest {

    @Test
    fun tapCarriesHandshakeAndNonPacketEnvelopes() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:raw-tap"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        val seen = mutableListOf<FromRadio>()
        val packets = mutableListOf<Any>()
        val tap = backgroundScope.launch { client.fromRadio.collect { seen += it } }
        val packetJob = backgroundScope.launch { client.packets.collect { packets += it } }
        client.connect()
        runCurrent()
        assertTrue(seen.any { it.my_info != null }, "handshake my_info should reach the tap")

        val record = LogRecord.Builder().also { wb -> wb.message = "tap me" }.build()
        transport.injectFrame(FromRadio.Builder().also { wb -> wb.log_record = record }.build().toFrame())
        runCurrent()

        assertEquals(1, seen.count { it.log_record == record })
        assertTrue(packets.isEmpty(), "a log record is not a mesh packet")

        tap.cancel()
        packetJob.cancel()
        runCatching { client.disconnect() }
    }
}
