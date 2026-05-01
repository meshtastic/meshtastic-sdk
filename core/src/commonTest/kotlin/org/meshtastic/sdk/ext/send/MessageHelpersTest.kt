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
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageHelpersTest {

    private fun TestScope.buildClient(): RadioClient = RadioClient.Builder()
        .transport(FakeRadioTransport(TransportIdentity("fake:helpers"), autoHandshake = true))
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    @Test
    fun sendPosition_returnsHandle() = runTest {
        val client = buildClient()
        client.connect()
        assertNotNull(client.sendPosition(LatLng(37.7749, -122.4194)))
        client.disconnect()
    }

    @Test
    fun requestPosition_returnsHandle() = runTest {
        val client = buildClient()
        client.connect()
        assertNotNull(client.requestPosition(NodeId(0x12345678)))
        client.disconnect()
    }

    @Test
    fun sendDirectMessage_returnsHandle() = runTest {
        val client = buildClient()
        client.connect()
        assertNotNull(client.sendDirectMessage(NodeId(0xa1b2c3d4.toInt()), "hi"))
        client.disconnect()
    }

    @Test
    fun sendDirectMessageEncrypted_returnsHandle() = runTest {
        val client = buildClient()
        client.connect()
        assertNotNull(client.sendDirectMessageEncrypted(NodeId(0x11223344), "secret"))
        client.disconnect()
    }

    @Test
    fun positionScale_smokeCheck() {
        assertEquals(377749000, (37.7749 * 1e7).toInt())
        assertTrue(PortNum.POSITION_APP.value > 0)
        ChannelIndex(0)
    }
}
