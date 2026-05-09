/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceTimerTest {
    private fun TestScope.connectedClient(
        storage: StorageProvider,
        myNodeNum: Int = 0x11111111,
        presenceTimeout: Duration = 1.seconds,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:presence-timer"),
            autoHandshake = true,
            nodeNum = myNodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(storage)
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .presenceTimeout(presenceTimeout)
            .build()
        return transport to client
    }

    @Test
    fun staleHeartbeatEmitsWentOfflineAndNewTrafficEmitsCameOnline() = runTest {
        val remoteNode = NodeId(0x22222222)
        val staleHeartbeatMs = Clock.System.now().toEpochMilliseconds() - 5.seconds.inWholeMilliseconds
        val storage = SeededHeartbeatStorageProvider(mapOf(remoteNode to staleHeartbeatMs))
        val (transport, client) = connectedClient(storage)

        val observed = mutableListOf<NodeChange>()
        val collector = backgroundScope.launch {
            client.nodes.collect { change ->
                when (change) {
                    is NodeChange.WentOffline, is NodeChange.CameOnline -> observed += change
                    else -> Unit
                }
            }
        }

        client.connect()
        runCurrent()

        advanceTimeBy(30.seconds)
        runCurrent()

        val wentOffline = observed.single { it is NodeChange.WentOffline }
        assertIs<NodeChange.WentOffline>(wentOffline)
        assertEquals(remoteNode, wentOffline.nodeId)
        assertEquals((staleHeartbeatMs / 1000).toInt(), wentOffline.lastHeard)

        transport.injectPacket(
            MeshPacket(
                from = remoteNode.raw,
                to = 0,
                decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP),
            ),
        )
        runCurrent()

        val cameOnline = observed.last()
        assertIs<NodeChange.CameOnline>(cameOnline)
        assertEquals(remoteNode, cameOnline.nodeId)

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun selfNodeIsNeverMarkedOffline() = runTest {
        val myNode = NodeId(0x11111111)
        val staleHeartbeatMs = Clock.System.now().toEpochMilliseconds() - 5.seconds.inWholeMilliseconds
        val storage = SeededHeartbeatStorageProvider(mapOf(myNode to staleHeartbeatMs))
        val (_, client) = connectedClient(storage, myNodeNum = myNode.raw)

        val observed = mutableListOf<NodeChange>()
        val collector = backgroundScope.launch {
            client.nodes.collect { change ->
                when (change) {
                    is NodeChange.WentOffline, is NodeChange.CameOnline -> observed += change
                    else -> Unit
                }
            }
        }

        client.connect()
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()

        assertTrue(observed.isEmpty())

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun freshNodesAreNotMarkedOffline() = runTest {
        val remoteNode = NodeId(0x33333333)
        val freshHeartbeatMs = Clock.System.now().toEpochMilliseconds()
        val storage = SeededHeartbeatStorageProvider(mapOf(remoteNode to freshHeartbeatMs))
        val (_, client) = connectedClient(storage, presenceTimeout = 60.seconds)

        val observed = mutableListOf<NodeChange>()
        val collector = backgroundScope.launch {
            client.nodes.collect { change ->
                when (change) {
                    is NodeChange.WentOffline, is NodeChange.CameOnline -> observed += change
                    else -> Unit
                }
            }
        }

        client.connect()
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()

        assertTrue(observed.isEmpty())

        collector.cancel()
        client.disconnect()
    }
}

private class SeededHeartbeatStorageProvider(private val heartbeats: Map<NodeId, Long>) : StorageProvider {
    override suspend fun activate(identity: TransportIdentity): DeviceStorage = InMemoryStorage().also { storage ->
        heartbeats.forEach { (nodeId, heartbeatMs) ->
            storage.saveHeartbeat(nodeId, heartbeatMs)
        }
    }
}
