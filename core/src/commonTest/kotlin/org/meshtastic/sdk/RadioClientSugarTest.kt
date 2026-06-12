/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Consumer-convenience surface: `RadioClient { }`, `withConnection { }`, `asNodeMap()`. */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioClientSugarTest {

    @Test
    fun dslFactoryBuildsAWorkingClient() = runTest {
        val transport = fakeTransport()
        val client = RadioClient {
            transport(transport)
            storage(InMemoryStorageProvider())
            coroutineContext(backgroundScope.coroutineContext)
            autoSyncTimeOnConnect(false)
        }

        client.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)
        client.disconnect()
        runCurrent()
    }

    @Test
    fun withConnectionRunsBlockConnectedAndAlwaysDisconnects() = runTest {
        val client = buildClient(fakeTransport())

        val state = client.withConnection { connection.value }

        assertEquals(ConnectionState.Connected, state, "Block must observe a connected session")
        runCurrent()
        assertEquals(ConnectionState.Disconnected, client.connection.value, "Session must be torn down")
    }

    @Test
    fun withConnectionDisconnectsWhenBlockThrows() = runTest {
        val client = buildClient(fakeTransport())

        assertFailsWith<IllegalStateException> {
            client.withConnection { error("boom") }
        }
        runCurrent()
        assertEquals(ConnectionState.Disconnected, client.connection.value, "Teardown must survive exceptions")
    }

    @Test
    fun asNodeMapFoldsTheCanonicalAccumulator() = runTest {
        val alice = NodeInfo(num = 1, user = User(id = "!1", long_name = "Alice"))
        val bob = NodeInfo(num = 2, user = User(id = "!2", long_name = "Bob"))
        val bobRenamed = NodeInfo(num = 2, user = User(id = "!2", long_name = "Bobby"))

        val emissions = flowOf(
            NodeChange.Snapshot(mapOf(NodeId(1) to alice)),
            NodeChange.Added(bob),
            NodeChange.Updated(bobRenamed, setOf(NodeField.Name)),
            NodeChange.WentOffline(NodeId(1), lastHeard = 0),
            NodeChange.Removed(NodeId(1)),
        ).asNodeMap().toList()

        assertEquals(emptyMap(), emissions.first(), "scan seeds with an empty map")
        assertEquals(mapOf(NodeId(1) to alice), emissions[1])
        assertEquals(mapOf(NodeId(1) to alice, NodeId(2) to bob), emissions[2])
        assertEquals(bobRenamed, emissions[3][NodeId(2)])
        assertEquals(emissions[3], emissions[4], "Presence deltas must not mutate the map")
        assertEquals(mapOf(NodeId(2) to bobRenamed), emissions.last())
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private fun fakeTransport(): FakeRadioTransport = FakeRadioTransport(
        identity = TransportIdentity("fake:sugar"),
        autoHandshake = true,
        nodeNum = 0x11111111,
    )

    private fun TestScope.buildClient(transport: FakeRadioTransport): RadioClient = RadioClient {
        transport(transport)
        storage(InMemoryStorageProvider())
        coroutineContext(backgroundScope.coroutineContext)
        autoSyncTimeOnConnect(false)
    }
}
