/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                myInfo = MyNodeInfo.Builder().also { wb -> wb.my_node_num = previousNodeNum }.build(),
                metadata = DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "1.0.0"
                }.build(),
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

    private fun id(seed: Int): ByteString = ByteArray(16) { (seed + it).toByte() }.toByteString()

    private class Outcome(val storage: InMemoryStorage, val events: List<MeshEvent>) {
        val rebinds get() = events.filterIsInstance<MeshEvent.IdentityRebound>()
    }

    /** Seeds [storage] as a prior session for [previousNodeNum] with [storedId], then connects a device reporting [newNodeNum] and [incomingId]. */
    private suspend fun TestScope.reconnect(
        previousNodeNum: Int,
        storedId: ByteString?,
        newNodeNum: Int,
        incomingId: ByteString,
    ): Outcome {
        val storage = InMemoryStorage()
        storage.recordOwnNode(NodeId(previousNodeNum), "1.0.0", storedId)
        storage.saveNode(NodeInfo.Builder().also { wb -> wb.num = previousNodeNum }.build())
        storage.saveNode(NodeInfo.Builder().also { wb -> wb.num = 777 }.build())
        storage.saveChannels(listOf(Channel.Builder().also { wb -> wb.index = 0 }.build()))
        storage.saveConfig(
            ConfigBundle(
                myInfo = MyNodeInfo.Builder().also { wb -> wb.my_node_num = previousNodeNum }.build(),
                metadata = DeviceMetadata.Builder().also { wb -> wb.firmware_version = "1.0.0" }.build(),
                configs = emptyList(),
                moduleConfigs = emptyList(),
            ),
        )
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:renumber"),
            autoHandshake = true,
            nodeNum = newNodeNum,
        ).also { it.deviceId = incomingId }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(PreloadedStorageProvider(storage))
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        val received = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { received += it } }
        runCurrent()
        client.connect()
        runCurrent()
        client.disconnect()
        return Outcome(storage, received.toList())
    }

    @Test
    fun sameDeviceIdWithNewNodeNumKeepsStorageAndEmitsNodeRenumbered() = runTest {
        val out = reconnect(123, id(1), 456, id(1))
        val rebind = out.rebinds.single()
        assertEquals("node_renumbered", rebind.reason)
        assertEquals(NodeId(123), rebind.previousNodeNum)
        assertEquals(NodeId(456), rebind.newNodeNum)
        assertEquals(1, out.storage.loadChannels().size)
        assertNotNull(out.storage.loadNodes()[NodeId(777)], "unrelated node row must survive")
        assertNotNull(out.storage.loadConfig())
        assertEquals(id(1), out.storage.loadOwnDeviceId())
    }

    @Test
    fun renumberRemovesTheOldOwnNodeRow() = runTest {
        val out = reconnect(123, id(1), 456, id(1))
        assertNull(out.storage.loadNodes()[NodeId(123)])
    }

    @Test
    fun differentDeviceIdClearsStorage() = runTest {
        val out = reconnect(123, id(1), 456, id(2))
        assertEquals("identity_mismatch", out.rebinds.single().reason)
        assertTrue(out.storage.loadChannels().isEmpty())
        assertNull(out.storage.loadNodes()[NodeId(777)])
    }

    @Test
    fun missingStoredDeviceIdClearsStorage() = runTest {
        val out = reconnect(123, null, 456, id(1))
        assertEquals("identity_mismatch", out.rebinds.single().reason)
        assertTrue(out.storage.loadChannels().isEmpty())
    }

    @Test
    fun missingIncomingDeviceIdClearsStorage() = runTest {
        val out = reconnect(123, id(1), 456, ByteString.EMPTY)
        assertEquals("identity_mismatch", out.rebinds.single().reason)
        assertTrue(out.storage.loadChannels().isEmpty())
    }

    @Test
    fun allZeroDeviceIdClearsStorage() = runTest {
        val zero = ByteArray(16).toByteString()
        val out = reconnect(123, zero, 456, zero)
        assertEquals("identity_mismatch", out.rebinds.single().reason)
        assertTrue(out.storage.loadChannels().isEmpty())
    }

    @Test
    fun wrongLengthDeviceIdClearsStorage() = runTest {
        val short = ByteArray(6) { 1 }.toByteString()
        val out = reconnect(123, short, 456, short)
        assertEquals("identity_mismatch", out.rebinds.single().reason)
        assertTrue(out.storage.loadChannels().isEmpty())
    }

    @Test
    fun sameNodeNumIsANoOpEvenWithDeviceId() = runTest {
        val out = reconnect(123, id(1), 123, id(1))
        assertTrue(out.rebinds.isEmpty(), "got ${out.events}")
        assertEquals(1, out.storage.loadChannels().size)
        assertNotNull(out.storage.loadNodes()[NodeId(123)])
    }
}
