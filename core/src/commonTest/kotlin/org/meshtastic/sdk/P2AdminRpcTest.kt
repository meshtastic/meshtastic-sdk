/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.Routing
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 2 — AdminApi RPC coverage. Each test wires a [FakeRadioTransport] with auto-handshake,
 * exercises one [AdminApi] method, and verifies (a) the outbound packet shape and (b) the
 * mapping from the scripted device response to the returned [AdminResult].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P2AdminRpcTest {

    private fun TestScope.connectedClient(nodeNum: Int = 1): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p2-admin"),
            autoHandshake = true,
            nodeNum = nodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false) // tests verify behavior explicitly; opt out of autosync.
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    @Test
    fun getOwnerSuccessReturnsUser() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        // Drain any auto-handshake packets so the next outbound is the getOwner.
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.getOwner() }
        runCurrent()

        // Locate the getOwner request packet and synthesize the response.
        val outbound = transport.outboundPackets().drop(outboundBefore)
        val getOwner = outbound.last { adminOf(it)?.get_owner_request == true }
        val expected = User(id = "!00000001", long_name = "Test", short_name = "T", hw_model = HardwareModel.UNSET)
        transport.injectAdminResponse(
            requestId = getOwner.id,
            response = AdminMessage(get_owner_response = expected),
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<User>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun getConfigSuccessReturnsConfig() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.getConfig(AdminMessage.ConfigType.LORA_CONFIG) }
        runCurrent()

        val getConfig = transport.outboundPackets().drop(outboundBefore)
            .last { adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG }
        val cfg = org.meshtastic.proto.Config(
            lora = org.meshtastic.proto.Config.LoRaConfig(use_preset = true),
        )
        transport.injectAdminResponse(
            requestId = getConfig.id,
            response = AdminMessage(get_config_response = cfg),
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<org.meshtastic.proto.Config>>(result)
        assertEquals(cfg, result.value)
        client.disconnect()
    }

    @Test
    fun setOwnerAckSurfacesAsSuccess() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val user = User(id = "!00000001", long_name = "Set", short_name = "S", hw_model = HardwareModel.UNSET)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.setOwner(user) }
        runCurrent()

        val setOwner = transport.outboundPackets().drop(outboundBefore)
            .last { adminOf(it)?.set_owner == user }
        // Setter expects want_ack = true so the engine arms the routing-ack timer.
        assertTrue(setOwner.want_ack, "setter must request a wire-level ack")
        transport.injectRoutingAck(requestId = setOwner.id)
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<Unit>>(result)
        client.disconnect()
    }

    @Test
    fun rebootMapsRoutingErrorToAdminFailure() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.reboot() }
        runCurrent()

        val reboot = transport.outboundPackets().drop(outboundBefore)
            .last { adminOf(it)?.reboot_seconds == 0 }
        transport.injectFrame(
            // Reuse the existing helper: a Routing.Error with NO_ROUTE matches the request_id.
            buildRoutingErrorFrame(reboot.id, Routing.Error.NO_ROUTE),
        )
        runCurrent()

        val result = deferred.await()
        // SendFailure.NoRoute → AdminResult.NodeUnreachable per the AdminApiImpl mapping.
        assertEquals(AdminResult.NodeUnreachable, result)
        client.disconnect()
    }

    @Test
    fun getOwnerTimeoutResolvesAsTimeout() = runTest {
        val (_, client) = connectedClient()
        client.connect()
        runCurrent()

        val deferred = async { client.admin.getOwner() }
        runCurrent()
        // No response injected; advance past the 60 s rpcTimeout.
        advanceTimeBy(70.seconds)
        runCurrent()

        val result = deferred.await()
        assertEquals(AdminResult.Timeout, result)
        client.disconnect()
    }

    @Test
    fun sessionKeyExpiredTriggersSingleShotRetry() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.getOwner() }
        runCurrent()

        // (1) First attempt is rejected with ADMIN_BAD_SESSION_KEY.
        val firstGetOwner = transport.outboundPackets().drop(outboundBefore)
            .last { adminOf(it)?.get_owner_request == true }
        transport.injectFrame(
            buildRoutingErrorFrame(firstGetOwner.id, Routing.Error.ADMIN_BAD_SESSION_KEY),
        )
        runCurrent()

        // (2) The retry path then issues a fresh re-seed getOwner. Satisfy it with a valid
        // response so the retry can proceed to the replay step.
        val reseedSlice = transport.outboundPackets().drop(outboundBefore + 1)
        val reseedReq = reseedSlice.first { adminOf(it)?.get_owner_request == true }
        val reseedUser = User(id = "!00000001", long_name = "Reseed", short_name = "R", hw_model = HardwareModel.UNSET)
        transport.injectAdminResponse(
            requestId = reseedReq.id,
            response = AdminMessage(get_owner_response = reseedUser),
        )
        runCurrent()
        advanceUntilIdle()

        // (3) After the re-seed completes, the original block is replayed. Satisfy that too.
        val replaySlice = transport.outboundPackets().drop(outboundBefore + 1 + reseedSlice.indexOf(reseedReq) + 1)
        val replayReq = replaySlice.first { adminOf(it)?.get_owner_request == true }
        val replayUser = User(id = "!00000001", long_name = "Replay", short_name = "X", hw_model = HardwareModel.UNSET)
        transport.injectAdminResponse(
            requestId = replayReq.id,
            response = AdminMessage(get_owner_response = replayUser),
        )
        runCurrent()
        advanceUntilIdle()

        val result = deferred.await()
        assertIs<AdminResult.Success<User>>(result)
        assertEquals(replayUser, result.value, "single-shot retry should surface the replay's value")
        client.disconnect()
    }

    @Test
    fun listChannelsHaltsOnDisabledRole() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.listChannels() }

        // Respond to up to 8 sequential get_channel_request slots; mark slot 2 as DISABLED so
        // the iteration stops early. Each request is allocated a fresh wire id.
        repeat(3) {
            runCurrent()
            val outbound = transport.outboundPackets().drop(outboundBefore)
            val req = outbound.lastOrNull { adminOf(it)?.get_channel_request != null }
            assertNotNull(req)
            val index = adminOf(req)!!.get_channel_request!!
            val channel = if (index < 2) {
                Channel(index = index, role = Channel.Role.PRIMARY)
            } else {
                Channel(index = index, role = Channel.Role.DISABLED)
            }
            transport.injectAdminResponse(
                requestId = req.id,
                response = AdminMessage(get_channel_response = channel),
            )
        }
        runCurrent()
        advanceUntilIdle()

        val result = deferred.await()
        assertIs<AdminResult.Success<List<Channel>>>(result)
        // Slots 0,1 collected; iteration halts at slot 2 (DISABLED).
        assertEquals(2, result.value.size)
        client.disconnect()
    }

    @Test
    fun setTimeUsesInjectedClock() = runTest {
        val frozen = kotlin.time.Instant.fromEpochSeconds(1_700_000_000L)
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p2-admin-clock"),
            autoHandshake = true,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .clock(object : kotlin.time.Clock {
                override fun now() = frozen
            })
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.admin.setTime() }
        runCurrent()

        val setTime = transport.outboundPackets().drop(outboundBefore)
            .last { adminOf(it)?.set_time_only != null }
        assertEquals(frozen.epochSeconds.toInt(), adminOf(setTime)!!.set_time_only)
        transport.injectRoutingAck(requestId = setTime.id)
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<Unit>>(result)
        client.disconnect()
    }

    @Test
    fun editSettingsBracketsBeginAndCommit() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async {
            client.admin.editSettings {
                setFavorite(NodeId(0xaabbccdd.toInt()), favorite = true)
            }
        }

        // Step 1: ack the begin_edit_settings packet.
        runCurrent()
        var outbound = transport.outboundPackets().drop(outboundBefore)
        val begin = outbound.last { adminOf(it)?.begin_edit_settings == true }
        transport.injectRoutingAck(requestId = begin.id)
        runCurrent()
        advanceUntilIdle()

        // Step 2: the inner setFavorite packet was enqueued without want_ack (engine path stops
        // tracking after Sent). The block returns. The commit packet is then sent and acked.
        outbound = transport.outboundPackets().drop(outboundBefore)
        val commit = outbound.last { adminOf(it)?.commit_edit_settings == true }
        transport.injectRoutingAck(requestId = commit.id)
        runCurrent()
        advanceUntilIdle()

        val result = deferred.await()
        assertIs<AdminResult.Success<Unit>>(result)
        // Verify the inner setFavorite was emitted between begin and commit.
        val orderedAdmin = transport.outboundPackets().mapNotNull { p -> adminOf(p)?.let { p to it } }
        val beginIdx = orderedAdmin.indexOfFirst { it.second.begin_edit_settings == true }
        val favIdx = orderedAdmin.indexOfFirst { it.second.set_favorite_node == 0xaabbccdd.toInt() }
        val commitIdx = orderedAdmin.indexOfFirst { it.second.commit_edit_settings == true }
        assertTrue(beginIdx in 0..<favIdx, "set_favorite must come after begin")
        assertTrue(favIdx < commitIdx, "set_favorite must come before commit")
        client.disconnect()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun adminOf(packet: org.meshtastic.proto.MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != org.meshtastic.proto.PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }

    private fun buildRoutingErrorFrame(requestId: Int, error: Routing.Error): Frame {
        val payload = okio.ByteString.of(*Routing.ADAPTER.encode(Routing(error_reason = error)))
        val packet = org.meshtastic.proto.MeshPacket(
            from = 1,
            to = 0,
            decoded = org.meshtastic.proto.Data(
                portnum = org.meshtastic.proto.PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        val fr = org.meshtastic.proto.FromRadio(packet = packet)
        val proto = org.meshtastic.proto.FromRadio.ADAPTER.encode(fr)
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

private typealias TestScope = kotlinx.coroutines.test.TestScope
