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
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A host logs what it sent from `outboundPackets` instead of rebuilding each packet. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboundPacketsTapTest {

    @Test
    fun `the tap carries each packet written for a send a paced send and an RPC`() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:outbound-tap"),
            autoHandshake = true,
            nodeNum = OWN_NODE,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        val tapped = mutableListOf<MeshPacket>()
        val sessionIds = mutableListOf<Long>()
        backgroundScope.launch {
            client.outboundPackets.collect {
                tapped += it.value
                sessionIds += it.sessionId
            }
        }
        runCurrent()
        val before = transport.outboundPackets().size

        val sent = client.send {
            text("logged")
            to(PEER)
            wantAck()
        }
        val paced = client.sendPaced {
            text("paced")
            to(PEER)
        }
        backgroundScope.launch { client.telemetry.requestDevice(PEER) }
        runCurrent()

        val written = transport.outboundPackets().drop(before)
        assertEquals(3, written.size)
        assertEquals(written, tapped)
        assertEquals(listOf(sent.id.raw, paced.message.id.raw), tapped.take(2).map { it.id })
        val rpc = tapped.last()
        assertEquals(PortNum.TELEMETRY_APP, rpc.decoded?.portnum)
        assertTrue(rpc.id != 0, "an RPC request is tapped under the id the SDK gave it")
        assertTrue(tapped.all { it.from == OWN_NODE })
        // Regression: the outbound tap stamped session 0 instead of the first handshake's id.
        assertEquals(setOf(1L), sessionIds.toSet())
        client.disconnect()
        runCurrent()
    }

    @Test
    fun `a lockdown command reaches the radio but never the tap`() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:outbound-tap-lockdown"),
            autoHandshake = true,
            nodeNum = OWN_NODE,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        val tapped = mutableListOf<MeshPacket>()
        backgroundScope.launch { client.outboundPackets.collect { tapped += it.value } }
        runCurrent()

        val accepted = client.tryLockdown(
            LockdownAuth.Builder().also {
                it.passphrase = "hunter2".encodeUtf8()
            }.build(),
        )
        runCurrent()

        assertTrue(accepted)
        assertTrue(transport.outboundPackets().any { it.carriesLockdownAuth() }, "the command was written")
        assertTrue(tapped.none { it.carriesLockdownAuth() }, "the passphrase stays off the tap")
        client.disconnect()
        runCurrent()
    }

    private fun MeshPacket.carriesLockdownAuth(): Boolean {
        val data = decoded ?: return false
        if (data.portnum != PortNum.ADMIN_APP) return false
        return AdminMessage.ADAPTER.decode(data.payload).lockdown_auth != null
    }

    private companion object {
        const val OWN_NODE = 0x11111111
        val PEER = NodeId(0x22222222)
    }
}
