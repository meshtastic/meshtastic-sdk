/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeFlowDropTest {
    @Test
    fun aNodeChangeDroppedByAStalledSubscriberIsReportedAsANodesDrop() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:node-drop"),
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        client.connect()

        val drops = mutableListOf<DroppedFlow>()
        backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.PacketsDropped) drops += it.flow }
        }
        backgroundScope.launch { client.nodes.collect { awaitCancellation() } }
        runCurrent()

        // More new nodes than the nodes flow buffers while its only subscriber is stalled.
        repeat(300) { index ->
            val num = 0x1000 + index
            transport.injectFrame(
                FromRadio.Builder().also { wb ->
                    wb.node_info = NodeInfo.Builder().also { nb ->
                        nb.num = num
                        nb.user = User.Builder().also { ub -> ub.id = "!" + num.toString(16).padStart(8, '0') }.build()
                    }.build()
                }.build().toFrame(),
            )
        }
        runCurrent()

        assertTrue(drops.isNotEmpty(), "expected a PacketsDropped for the stalled nodes subscriber")
        assertTrue(drops.all { it == DroppedFlow.Nodes }, "node drops must name the nodes flow, got $drops")
    }
}
