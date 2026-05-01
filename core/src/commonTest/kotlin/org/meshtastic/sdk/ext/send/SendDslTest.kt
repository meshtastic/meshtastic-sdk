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
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SendDslTest {

    private fun TestScope.buildClient(): RadioClient = RadioClient.Builder()
        .transport(FakeRadioTransport(TransportIdentity("fake:test"), autoHandshake = true))
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    @Test
    fun dsl_textHappyPath() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send {
            text("hello world")
            to(NodeId(0x1234abcd))
            channel(ChannelIndex(2))
            wantAck()
            hopLimit(3)
        }
        assertNotNull(handle)
        client.disconnect()
    }

    @Test
    fun dsl_dataPortnumPayload() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send {
            data(PortNum.PRIVATE_APP, byteArrayOf(1, 2, 3))
        }
        assertNotNull(handle)
        client.disconnect()
    }

    @Test
    fun dsl_positionEncoding() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send {
            position(LatLng(37.7749, -122.4194, 12))
        }
        assertNotNull(handle)
        client.disconnect()
    }

    @Test
    fun dsl_protoEscapeHatch() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send {
            proto(MeshPacket(to = 0x42, channel = 1))
        }
        assertNotNull(handle)
        client.disconnect()
    }

    @Test
    fun dsl_protoForbidsConvenienceSetters() {
        val builder = SendBuilder()
        builder.proto(MeshPacket())
        assertFailsWith<IllegalStateException> { builder.to(NodeId.BROADCAST) }
        assertFailsWith<IllegalStateException> { builder.channel(ChannelIndex(0)) }
        assertFailsWith<IllegalStateException> { builder.wantAck() }
        assertFailsWith<IllegalStateException> { builder.hopLimit(2) }
    }

    @Test
    fun dsl_emptyBuilderRejected() = runTest {
        val client = buildClient()
        client.connect()
        assertFailsWith<IllegalStateException> {
            client.send { /* nothing */ }
        }
        client.disconnect()
    }

    @Test
    fun dsl_multiplePayloadsRejected() = runTest {
        val client = buildClient()
        client.connect()
        assertFailsWith<IllegalStateException> {
            client.send {
                text("a")
                data(PortNum.PRIVATE_APP, byteArrayOf(1))
            }
        }
        client.disconnect()
    }
}
