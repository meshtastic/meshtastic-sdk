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
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
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
        const val BEGIN = "begin"
        const val COMMIT = "commit"
    }
}
