/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R-9 / DX-P0-14 — verifies the engine surfaces an identity rebind via
 * [MeshEvent.IdentityRebound] when the device reports a different `NodeNum` than the one
 * previously persisted by storage. The event MUST be emitted before any new
 * [NodeChange.Snapshot] lands and before the storage layer's `clear()` runs.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentityRebindTest {

    private class PreloadedStorageProvider(private val storage: InMemoryStorage) : StorageProvider {
        override suspend fun activate(identity: TransportIdentity): DeviceStorage = storage
    }

    @Test
    fun emitsIdentityReboundWhenNodeNumChanges() = runTest {
        val previousNodeNum = 123
        val newNodeNum = 456

        // Pre-seed storage as if a previous session with NodeNum 123 had completed.
        val storage = InMemoryStorage()
        storage.recordOwnNode(NodeId(previousNodeNum), "1.0.0")
        storage.saveConfig(
            ConfigBundle(
                myInfo = MyNodeInfo(my_node_num = previousNodeNum),
                metadata = DeviceMetadata(firmware_version = "1.0.0"),
                configs = emptyList(),
                moduleConfigs = emptyList(),
            ),
        )

        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:rebind"),
            autoHandshake = true,
            nodeNum = newNodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(PreloadedStorageProvider(storage))
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        // Subscribe to events BEFORE connect so we don't miss the synchronous emission.
        val received = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { received += it } }
        runCurrent()

        client.connect()
        runCurrent()

        val rebinds = received.filterIsInstance<MeshEvent.IdentityRebound>()
        assertEquals(1, rebinds.size, "expected exactly one IdentityRebound event, got $received")
        val rebind = rebinds.single()
        assertEquals(NodeId(previousNodeNum), rebind.previousNodeNum)
        assertEquals(NodeId(newNodeNum), rebind.newNodeNum)

        client.disconnect()
    }

    @Test
    fun doesNotEmitIdentityReboundOnFirstConnect() = runTest {
        // No prior identity persisted — connecting fresh must NOT emit IdentityRebound.
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:fresh"),
            autoHandshake = true,
            nodeNum = 42,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(PreloadedStorageProvider(InMemoryStorage()))
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val received = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { received += it } }
        runCurrent()

        client.connect()
        runCurrent()

        assertTrue(
            received.none { it is MeshEvent.IdentityRebound },
            "first connect must not emit IdentityRebound; got $received",
        )

        client.disconnect()
    }
}
