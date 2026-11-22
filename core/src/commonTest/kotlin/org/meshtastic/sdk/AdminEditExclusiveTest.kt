/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Firmware keeps one open edit transaction per node (`hasOpenEditTransaction` in `AdminModule.cpp`):
 * a second `begin_edit_settings` is absorbed and the first commit saves both callers' writes. So the
 * wire must carry one caller's begin, writes and answered commit before the next caller's begin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminEditExclusiveTest {

    private val deviceConfig = Config.Builder().also { it.device = Config.DeviceConfig.Builder().build() }.build()
    private val displayConfig = Config.Builder().also { it.display = Config.DisplayConfig.Builder().build() }.build()

    @Test
    fun twoEditsToTheConnectedNodeRunOneAfterTheOtherAcrossInstances() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size

        val first = async { client.admin.editSettings { setConfig(deviceConfig) } }
        val second = async { client.admin.forNode(NodeId(LOCAL)).batch { setConfig(displayConfig) } }
        runCurrent()
        assertEquals(listOf(BEGIN), ops(transport, before))

        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        // The commit is in flight but unanswered, so the first envelope is still open.
        assertEquals(listOf(BEGIN, "set:device", COMMIT), ops(transport, before))

        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()

        assertEquals(
            listOf(BEGIN, "set:device", COMMIT, BEGIN, "set:display", COMMIT),
            ops(transport, before),
        )
        assertIs<AdminResult.Success<Unit>>(first.await())
        second.await()
        client.disconnect()
    }

    @Test
    fun anEditToAnotherNodeDoesNotWaitForAnOpenLocalEdit() = runTest {
        val remote = NodeId(0x20202020)
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val gate = CompletableDeferred<Unit>()

        val local = async { client.admin.editSettings { gate.await() } }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()

        val remoteEdit = async {
            client.admin.forNode(remote).editSettings { setFavorite(NodeId(0x30303030), favorite = true) }
        }
        runCurrent()
        transport.injectRoutingAck(
            latest(transport, before) {
                it.begin_edit_settings == true
            }.id,
            fromNode = remote.raw,
        )
        runCurrent()
        transport.injectRoutingAck(
            latest(transport, before) {
                it.commit_edit_settings == true
            }.id,
            fromNode = remote.raw,
        )
        runCurrent()

        assertIs<AdminResult.Success<Unit>>(remoteEdit.await())
        assertFalse(local.isCompleted, "the local edit is still open")

        gate.complete(Unit)
        runCurrent()
        transport.injectRoutingAck(
            transport.outboundPackets().drop(before).last { p ->
                p.to == LOCAL && adminOf(p)?.commit_edit_settings == true
            }.id,
        )
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(local.await())
        client.disconnect()
    }

    @Test
    fun editsToTwoRemoteNodesDoNotWaitForEachOther() = runTest {
        val remoteA = NodeId(0x20202020)
        val remoteB = NodeId(0x40404040)
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val gate = CompletableDeferred<Unit>()
        fun last(to: NodeId, predicate: (AdminMessage) -> Boolean) = transport.outboundPackets().drop(before)
            .last { it.to == to.raw && adminOf(it)?.let(predicate) == true }

        val editA = async { client.admin.forNode(remoteA).editSettings { gate.await() } }
        runCurrent()
        transport.injectRoutingAck(last(remoteA) { it.begin_edit_settings == true }.id, fromNode = remoteA.raw)
        runCurrent()

        val editB = async {
            client.admin.forNode(remoteB).editSettings { setFavorite(NodeId(0x30303030), favorite = true) }
        }
        runCurrent()
        transport.injectRoutingAck(last(remoteB) { it.begin_edit_settings == true }.id, fromNode = remoteB.raw)
        runCurrent()
        transport.injectRoutingAck(last(remoteB) { it.commit_edit_settings == true }.id, fromNode = remoteB.raw)
        runCurrent()

        assertIs<AdminResult.Success<Unit>>(editB.await())
        assertFalse(editA.isCompleted, "the edit to the first remote node is still open")

        gate.complete(Unit)
        runCurrent()
        transport.injectRoutingAck(last(remoteA) { it.commit_edit_settings == true }.id, fromNode = remoteA.raw)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(editA.await())
        client.disconnect()
    }

    @Test
    fun theConnectedNodeKeepsOneQueueAcrossARenumber() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val gate = CompletableDeferred<Unit>()

        val first = async { client.admin.editSettings { gate.await() } }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()

        renumber(transport)

        val byClient = async { client.admin.editSettings { setConfig(deviceConfig) } }
        val byNewNumber = async { client.admin.forNode(NodeId(RENUMBERED)).editSettings { setConfig(displayConfig) } }
        runCurrent()
        assertEquals(listOf(BEGIN), ops(transport, before), "both wait for the edit opened under the old number")

        gate.complete(Unit)
        runCurrent()
        repeat(2) {
            transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id, RENUMBERED)
            runCurrent()
            transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id, RENUMBERED)
            runCurrent()
        }
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id, RENUMBERED)
        runCurrent()

        assertEquals(
            listOf(BEGIN, COMMIT, BEGIN, "set:device", COMMIT, BEGIN, "set:display", COMMIT),
            ops(transport, before),
        )
        assertIs<AdminResult.Success<Unit>>(first.await())
        assertIs<AdminResult.Success<Unit>>(byClient.await())
        assertIs<AdminResult.Success<Unit>>(byNewNumber.await())
        client.disconnect()
    }

    @Test
    fun aCallerQueuedOnTheNumberTheNodeMovesToWaitsForTheConnectedNodesEdit() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val newNumber = NodeId(RENUMBERED)
        val localGate = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()

        val local = async { client.admin.editSettings { localGate.await() } }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        // Before the renumber the new number is another node, so these two queue on its own lock.
        val holder = async { client.admin.forNode(newNumber).editSettings { remoteGate.await() } }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id, RENUMBERED)
        runCurrent()
        val queued = async { client.admin.forNode(newNumber).editSettings { setConfig(displayConfig) } }
        runCurrent()

        renumber(transport)
        remoteGate.complete(Unit)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id, RENUMBERED)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(holder.await())
        assertEquals(
            listOf(BEGIN, BEGIN, COMMIT),
            ops(transport, before),
            "the queued caller is now on the connected node",
        )

        localGate.complete(Unit)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id, RENUMBERED)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id, RENUMBERED)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id, RENUMBERED)
        runCurrent()

        assertEquals(
            listOf(BEGIN, BEGIN, COMMIT, COMMIT, BEGIN, "set:display", COMMIT),
            ops(transport, before),
        )
        assertIs<AdminResult.Success<Unit>>(local.await())
        assertIs<AdminResult.Success<Unit>>(queued.await())
        client.disconnect()
    }

    @Test
    fun editsNestedInOppositeOrdersFailInsteadOfDeadlocking() = runTest {
        val remote = NodeId(0x20202020)
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val localGate = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        fun last(to: Int, predicate: (AdminMessage) -> Boolean) = transport.outboundPackets().drop(before)
            .last { it.to == to && adminOf(it)?.let(predicate) == true }

        val localThenRemote = async {
            runCatching {
                client.admin.editSettings {
                    localGate.await()
                    client.admin.forNode(remote).editSettings { }
                }
            }
        }
        val remoteThenLocal = async {
            runCatching {
                client.admin.forNode(remote).editSettings {
                    remoteGate.await()
                    client.admin.editSettings { }
                }
            }
        }
        runCurrent()
        transport.injectRoutingAck(last(LOCAL) { it.begin_edit_settings == true }.id)
        transport.injectRoutingAck(last(remote.raw) { it.begin_edit_settings == true }.id, remote.raw)
        runCurrent()
        localGate.complete(Unit)
        remoteGate.complete(Unit)
        runCurrent()
        transport.injectRoutingAck(last(LOCAL) { it.commit_edit_settings == true }.id)
        transport.injectRoutingAck(last(remote.raw) { it.commit_edit_settings == true }.id, remote.raw)
        runCurrent()

        assertIs<IllegalStateException>(localThenRemote.await().exceptionOrNull())
        assertIs<IllegalStateException>(remoteThenLocal.await().exceptionOrNull())
        client.disconnect()
    }

    @Test
    fun anUncontendedEditNestedForAnotherNodeRuns() = runTest {
        val remote = NodeId(0x20202020)
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size

        val outer = async {
            client.admin.editSettings {
                client.admin.forNode(remote).editSettings { setFavorite(NodeId(0x30303030), favorite = true) }
            }
        }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id, remote.raw)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id, remote.raw)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()

        val result = assertIs<AdminResult.Success<AdminResult<Unit>>>(outer.await())
        assertIs<AdminResult.Success<Unit>>(result.value)
        client.disconnect()
    }

    @Test
    fun aCallerCancelledWhileQueuedLeavesTheNextOneItsTurn() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val gate = CompletableDeferred<Unit>()

        val first = async { client.admin.editSettings { gate.await() } }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()

        val cancelled = async { client.admin.editSettings { setConfig(displayConfig) } }
        runCurrent()
        cancelled.cancel(CancellationException("caller gone"))
        runCurrent()
        val third = async { client.admin.editSettings { setConfig(deviceConfig) } }
        runCurrent()
        assertEquals(listOf(BEGIN), ops(transport, before))

        gate.complete(Unit)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()

        assertEquals(listOf(BEGIN, COMMIT, BEGIN, "set:device", COMMIT), ops(transport, before))
        assertIs<AdminResult.Success<Unit>>(first.await())
        assertIs<AdminResult.Success<Unit>>(third.await())
        assertTrue(cancelled.isCancelled)
        client.disconnect()
    }

    @Test
    fun anEditNestedForTheSameNodeThrowsInsteadOfWaitingOnItself() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size

        val outer = async {
            runCatching { client.admin.editSettings { client.admin.forNode(NodeId(LOCAL)).editSettings { } } }
        }
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(latest(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()

        assertIs<IllegalStateException>(outer.await().exceptionOrNull())
        assertEquals(listOf(BEGIN, COMMIT), ops(transport, before))
        client.disconnect()
    }

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:admin-edit-exclusive"),
            autoHandshake = true,
            autoQueueStatus = true,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    // Firmware 2.8.1+ moves the node to a new number mid-session and resends my_info.
    private fun TestScope.renumber(transport: FakeRadioTransport) {
        transport.nodeNum = RENUMBERED
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.my_info = MyNodeInfo.Builder().also { it.my_node_num = RENUMBERED }.build()
            }.build().toFrame(),
        )
        runCurrent()
    }

    private fun ops(transport: FakeRadioTransport, before: Int): List<String> =
        transport.outboundPackets().drop(before).mapNotNull(::adminOf).mapNotNull { admin ->
            when {
                admin.begin_edit_settings == true -> BEGIN
                admin.commit_edit_settings == true -> COMMIT
                admin.set_config?.device != null -> "set:device"
                admin.set_config?.display != null -> "set:display"
                else -> null
            }
        }

    private fun latest(transport: FakeRadioTransport, before: Int, predicate: (AdminMessage) -> Boolean): MeshPacket =
        transport.outboundPackets().drop(before).last { adminOf(it)?.let(predicate) == true }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }

    private companion object {
        const val LOCAL = 1
        const val RENUMBERED = 0x5EED0001
        const val BEGIN = "begin"
        const val COMMIT = "commit"
    }
}
