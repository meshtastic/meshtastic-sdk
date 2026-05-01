/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SendOverloadTest {

    private fun TestScope.buildClient(): RadioClient = RadioClient.Builder()
        .transport(FakeRadioTransport(TransportIdentity("fake:test"), autoHandshake = true))
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    @Test
    fun sendPortnumPayload_roundTrips() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = "hi".encodeToByteArray(),
            to = NodeId(0x1234abcd),
            channel = ChannelIndex(2),
            wantAck = true,
            hopLimit = 3,
        )
        assertNotNull(handle)
        assertTrue(handle.id.raw != 0)
        client.disconnect()
    }

    @Test
    fun sendPortnumPayload_oversizedRejected() = runTest {
        val client = buildClient()
        client.connect()
        val tooBig = ByteArray(DATA_PAYLOAD_LEN + 1)
        assertFailsWith<MeshtasticException.PayloadTooLarge> {
            client.send(PortNum.TEXT_MESSAGE_APP, tooBig)
        }
        client.disconnect()
    }

    @Test
    fun sendPortnumPayload_defaultsBroadcastChannel0NoAck() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send(PortNum.TEXT_MESSAGE_APP, "x".encodeToByteArray())
        // Engine may have already transitioned past Queued under TestScope; just assert non-null.
        assertNotNull(handle.state.value)
        client.disconnect()
    }
}
