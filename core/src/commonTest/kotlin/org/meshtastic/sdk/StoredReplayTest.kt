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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * After `config_complete_id` firmware replays each node's stored position and telemetry as packets
 * marked `TRANSPORT_LORA` with no `rx_rssi`, and `rx_time` only when the stored `last_heard` is a
 * wall-clock time (`PhoneAPI::makeReplayPositionPacket`). A radio receive always sets `rx_rssi` and
 * leaves `rx_time` unset while the clock is untrusted (`Router::handleReceived`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredReplayTest {

    @Test
    fun aReplayWithoutRxTimeLeavesLastHeardUnchanged() = runTest {
        val (transport, client) = connected()
        transport.injectPacket(live(rxTime = HEARD_AT))
        runCurrent()
        val changes = collectChanges(client)

        transport.injectPacket(replay())
        runCurrent()

        assertEquals(HEARD_AT, client.nodeSnapshot()[NodeId(PEER)]?.last_heard)
        assertTrue(
            changes.none { it is NodeChange.Updated && NodeField.LastSeen in it.changed },
            "a replay moved last_heard: $changes",
        )
        client.disconnect()
    }

    @Test
    fun aLiveLoRaPacketWithoutRxTimeIsStampedNow() = runTest {
        val (transport, client) = connected()
        advanceTimeBy(5.seconds)

        transport.injectPacket(live(rxTime = 0))
        runCurrent()

        val now = ((EPOCH_MS + currentTime) / 1000).toInt()
        assertEquals(now, client.nodeSnapshot()[NodeId(PEER)]?.last_heard)
        client.disconnect()
    }

    @Test
    fun aReplayDoesNotBringAnOfflineNodeBackOnline() = runTest {
        val (transport, client) = connected(presenceTimeout = 1.seconds)
        transport.injectPacket(live(rxTime = HEARD_AT))
        runCurrent()
        val changes = collectChanges(client)
        advanceTimeBy(40.seconds)
        runCurrent()
        assertTrue(changes.any { it is NodeChange.WentOffline && it.nodeId == NodeId(PEER) }, "$changes")

        transport.injectPacket(replay())
        runCurrent()
        assertTrue(changes.none { it is NodeChange.CameOnline }, "a replay counted as presence: $changes")

        transport.injectPacket(live(rxTime = HEARD_AT + 60))
        runCurrent()
        assertTrue(changes.any { it is NodeChange.CameOnline && it.nodeId == NodeId(PEER) }, "$changes")
        client.disconnect()
    }

    private fun live(rxTime: Int): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.id = rxTime + 1
        wb.from = PEER
        wb.to = BROADCAST
        // Absent, as firmware leaves it while its clock is untrusted.
        wb.rx_time = rxTime.takeIf { it != 0 }
        wb.rx_rssi = -80
        wb.rx_snr = 6f
        wb.transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.TEXT_MESSAGE_APP
            d.payload = "hi".encodeUtf8()
        }.build()
    }.build()

    // The shape PhoneAPI::makeReplayPositionPacket gives a node whose last_heard is not a wall-clock time.
    private fun replay(): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.id = 0x7E57
        wb.from = PEER
        wb.to = BROADCAST
        wb.priority = MeshPacket.Priority.BACKGROUND
        wb.transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.POSITION_APP
            d.payload = Position.ADAPTER.encode(
                Position.Builder().also { p ->
                    p.latitude_i = 374_220_000
                    p.longitude_i = -1_220_840_000
                }.build(),
            ).toByteString()
        }.build()
    }.build()

    private fun TestScope.collectChanges(client: RadioClient): MutableList<NodeChange> {
        val out = mutableListOf<NodeChange>()
        backgroundScope.launch { client.nodes.collect { out += it } }
        runCurrent()
        return out
    }

    private suspend fun TestScope.connected(
        presenceTimeout: Duration = Duration.INFINITE,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:stored-replay"),
            autoHandshake = true,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .clock(
                object : Clock {
                    override fun now() = Instant.fromEpochMilliseconds(EPOCH_MS + currentTime)
                },
            )
            .apply { if (presenceTimeout.isFinite()) presenceTimeout(presenceTimeout) }
            .build()
        client.connect()
        runCurrent()
        return transport to client
    }

    private companion object {
        const val LOCAL = 1
        const val PEER = 0x2222
        const val BROADCAST = -1
        const val HEARD_AT = 1_700_000_000
        const val EPOCH_MS = 1_800_000_000_000L
    }
}
