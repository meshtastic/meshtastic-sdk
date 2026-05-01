/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.Channel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * P1-5 + P1-6 — storage exception wrapping and heartbeat persistence.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageResilienceTest {

    private class FixedStorageProvider(private val storage: DeviceStorage) : StorageProvider {
        override suspend fun activate(identity: TransportIdentity): DeviceStorage = storage
    }

    /** Throws on every write-path method; reads succeed and return empty. */
    private class ThrowingWriteStorage : DeviceStorage {
        var saveNodeCalls = 0
        override suspend fun loadNodes(): Map<NodeId, NodeInfo> = emptyMap()
        override suspend fun saveNode(node: NodeInfo) {
            saveNodeCalls++
            error("disk full (simulated)")
        }
        override suspend fun removeNode(nodeId: NodeId) = error("disk full")
        override suspend fun loadConfig(): ConfigBundle? = null
        override suspend fun saveConfig(config: ConfigBundle) = error("disk full")
        override suspend fun loadChannels(): List<Channel> = emptyList()
        override suspend fun saveChannels(channels: List<Channel>) = error("disk full")
        override suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String) = error("disk full")
        override suspend fun clear() {}
        override suspend fun saveSessionPasskey(passkey: SessionPasskey) = error("disk full")
        override suspend fun loadSessionPasskey(): SessionPasskey? = null
        override suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long) = error("disk full")
        override suspend fun loadHeartbeats(): Map<NodeId, Long> = emptyMap()
        override fun close() {}
    }

    // P1-5: a storage backend that throws on writes must NOT crash the engine; instead it
    // surfaces a single MeshEvent.StorageDegraded and keeps the in-memory session alive.
    @Test
    fun throwingStorageEmitsStorageDegradedAndDoesNotCrash() = runTest {
        val storage = ThrowingWriteStorage()
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:degraded"),
            autoHandshake = true,
            nodeNum = 42,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(FixedStorageProvider(storage))
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val events = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { events += it } }
        runCurrent()

        client.connect()
        runCurrent()

        // Session is up even though every persistence attempt failed.
        assertEquals(ConnectionState.Connected, client.connection.value)
        val degraded = events.filterIsInstance<MeshEvent.StorageDegraded>()
        assertEquals(1, degraded.size, "expected exactly one StorageDegraded, got $events")
        assertTrue(
            degraded.single().reason.isNotEmpty(),
            "reason should be human-readable, got '${degraded.single().reason}'",
        )

        client.disconnect()
    }

    // P1-6: packets received post-Ready bump the per-node heartbeat timestamp, and that state
    // is flushed to storage on the next heartbeat tick so presence survives process death.
    @Test
    fun heartbeatPersistenceRoundTrips() = runTest {
        val storage = InMemoryStorage()
        val provider = FixedStorageProvider(storage)

        val t1 = FakeRadioTransport(
            identity = TransportIdentity("fake:hb"),
            autoHandshake = true,
            nodeNum = 7,
        )
        val c1 = RadioClient.Builder()
            .transport(t1)
            .storage(provider)
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        c1.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, c1.connection.value)

        // Inject a mesh packet from a peer — the engine should record it as a presence signal.
        val peerId = 0x1234
        val peerPacket = org.meshtastic.proto.MeshPacket(from = peerId, to = 7, id = 1)
        val fromRadio = org.meshtastic.proto.FromRadio(packet = peerPacket)
        t1.injectFrame(framed(fromRadio))

        // Advance past one heartbeat tick so flushDirtyHeartbeats() drains the dirty set.
        advanceTimeBy(31.seconds)
        runCurrent()

        val persisted = storage.loadHeartbeats()
        assertTrue(
            NodeId(peerId) in persisted,
            "peer heartbeat should be persisted after tick, got $persisted",
        )
        val firstTs = persisted.getValue(NodeId(peerId))

        c1.disconnect()
        runCurrent()

        // Fresh engine + fresh transport, same storage: hydration must surface the prior ts.
        val t2 = FakeRadioTransport(
            identity = TransportIdentity("fake:hb"),
            autoHandshake = true,
            nodeNum = 7,
        )
        val c2 = RadioClient.Builder()
            .transport(t2)
            .storage(provider)
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        c2.connect()
        runCurrent()

        val afterRestart = storage.loadHeartbeats()
        assertEquals(
            firstTs,
            afterRestart[NodeId(peerId)],
            "heartbeat timestamp must survive reconnect unchanged",
        )

        c2.disconnect()
    }

    private fun framed(fromRadio: org.meshtastic.proto.FromRadio): Frame {
        val proto = org.meshtastic.proto.FromRadio.ADAPTER.encode(fromRadio)
        val bytes = ByteArray(4 + proto.size).apply {
            this[0] = 0x94.toByte()
            this[1] = 0xC3.toByte()
            this[2] = (proto.size shr 8).toByte()
            this[3] = (proto.size and 0xFF).toByte()
            proto.copyInto(this, destinationOffset = 4)
        }
        return Frame(kotlinx.io.bytestring.ByteString(bytes))
    }
}
