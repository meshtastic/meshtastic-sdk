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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AdminSessionStatusTest {

    @Test
    fun localSessionIsActiveAfterTheHandshakeSeedsIt() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()

        val status = client.adminSessions.value[LOCAL]
        val now = Instant.fromEpochMilliseconds(currentTime)
        assertEquals(AdminSessionStatus.Active(refreshedAt = now, staleAt = now + 150.seconds), status)
        assertEquals(status, client.adminSessionStatus(LOCAL).first())
    }

    @Test
    fun unknownNodeHasNoSession() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()

        assertEquals(AdminSessionStatus.NoSession, client.adminSessionStatus(REMOTE).first())
        assertFalse(REMOTE in client.adminSessions.value)
    }

    @Test
    fun remoteSessionIsActiveOnceTheTargetIssuesAPasskey() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()

        answerRemoteMetadata(client, device, REMOTE_PASSKEY)

        val now = Instant.fromEpochMilliseconds(currentTime)
        assertEquals(
            AdminSessionStatus.Active(refreshedAt = now, staleAt = now + 150.seconds),
            client.adminSessions.value[REMOTE],
        )
        assertIs<AdminSessionStatus.Active>(client.adminSessions.value[LOCAL])
    }

    @Test
    fun sessionGoesStaleThenNoSessionAsFirmwareLifetimePasses() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()
        val issuedAt = Instant.fromEpochMilliseconds(currentTime)

        keepAlive(device, 120.seconds.inWholeMilliseconds)
        assertIs<AdminSessionStatus.Active>(client.adminSessions.value[LOCAL])

        keepAlive(device, 40.seconds.inWholeMilliseconds)
        assertEquals(
            AdminSessionStatus.Stale(refreshedAt = issuedAt, expiresAt = issuedAt + 300.seconds),
            client.adminSessions.value[LOCAL],
        )

        keepAlive(device, 130.seconds.inWholeMilliseconds)
        assertEquals(
            AdminSessionStatus.Stale(refreshedAt = issuedAt, expiresAt = issuedAt + 300.seconds),
            client.adminSessions.value[LOCAL],
        )

        keepAlive(device, 20.seconds.inWholeMilliseconds)
        assertEquals(AdminSessionStatus.NoSession, client.adminSessionStatus(LOCAL).first())
        assertFalse(LOCAL in client.adminSessions.value)
    }

    @Test
    fun anotherAdminResponseRefreshesAStaleSession() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()
        answerRemoteMetadata(client, device, REMOTE_PASSKEY)

        keepAlive(device, 160.seconds.inWholeMilliseconds)
        assertIs<AdminSessionStatus.Stale>(client.adminSessions.value[REMOTE])

        answerRemoteMetadata(client, device, REMOTE_PASSKEY)
        assertIs<AdminSessionStatus.Active>(client.adminSessions.value[REMOTE])
    }

    @Test
    fun badSessionKeyDropsOnlyThatNodesSession() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()
        answerRemoteMetadata(client, device, REMOTE_PASSKEY)
        assertIs<AdminSessionStatus.Active>(client.adminSessions.value[REMOTE])

        val before = device.outboundPackets().size
        val call = backgroundScope.async { client.admin.forNode(REMOTE).getDeviceMetadata() }
        runCurrent()
        val request = device.outboundPackets().drop(before).last { it.to == REMOTE.raw }
        device.inject(routingError(request.id, REMOTE.raw))
        runCurrent()

        assertFalse(REMOTE in client.adminSessions.value)
        call.cancel()
        assertIs<AdminSessionStatus.Active>(client.adminSessions.value[LOCAL])
    }

    @Test
    fun sessionsAreClearedOnDisconnect() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()
        answerRemoteMetadata(client, device, REMOTE_PASSKEY)
        assertEquals(setOf(LOCAL, REMOTE), client.adminSessions.value.keys)

        client.disconnect()
        runCurrent()

        assertTrue(client.adminSessions.value.isEmpty())
        assertEquals(AdminSessionStatus.NoSession, client.adminSessionStatus(LOCAL).first())
    }

    @Test
    fun passkeyBytesNeverAppearInTheStatus() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()
        answerRemoteMetadata(client, device, REMOTE_PASSKEY)

        val rendered = client.adminSessions.value.toString()
        assertFalse(rendered.contains(REMOTE_PASSKEY.joinToString(",")))
        assertFalse(rendered.contains(REMOTE_PASSKEY.toByteString().hex()))
    }

    @Test
    fun keyBufferedDuringTheHandshakeIsStampedAtReceipt() = runTest {
        val device = SessionDevice(holdOwner = true)
        val client = buildClient(device)
        val connect = backgroundScope.async { client.connect() }
        repeat(100) {
            if (!device.ownerHeld) {
                advanceTimeBy(100)
                runCurrent()
            }
        }
        assertTrue(device.ownerHeld, "handshake must reach the owner request")
        advanceTimeBy(5_000)
        runCurrent()
        device.releaseOwner()
        runCurrent()
        connect.await()

        val remote = assertIs<AdminSessionStatus.Active>(client.adminSessions.value[REMOTE])
        val local = assertIs<AdminSessionStatus.Active>(client.adminSessions.value[LOCAL])
        val gap = local.refreshedAt - remote.refreshedAt
        assertTrue(gap >= 5.seconds && gap < 8.seconds, "remote key must keep its receipt time, gap was $gap")
    }

    @Test
    fun lateRejectionOfAnOlderKeyKeepsTheNewerKey() = runTest {
        val device = SessionDevice()
        val client = buildClient(device)
        client.connect()
        runCurrent()
        answerRemoteMetadata(client, device, REMOTE_PASSKEY)

        val before = device.outboundPackets().size
        val older = backgroundScope.async { client.admin.forNode(REMOTE).getDeviceMetadata() }
        runCurrent()
        val olderRequest = device.outboundPackets().drop(before).last { it.to == REMOTE.raw }

        answerRemoteMetadata(client, device, NEWER_PASSKEY)
        val newer = assertIs<AdminSessionStatus.Active>(client.adminSessions.value[REMOTE])

        device.inject(routingError(olderRequest.id, REMOTE.raw))
        runCurrent()
        assertEquals(newer, client.adminSessions.value[REMOTE])
        older.cancel()

        val beforeCurrent = device.outboundPackets().size
        val current = backgroundScope.async { client.admin.forNode(REMOTE).getDeviceMetadata() }
        runCurrent()
        val currentRequest = device.outboundPackets().drop(beforeCurrent).last { it.to == REMOTE.raw }
        device.inject(routingError(currentRequest.id, REMOTE.raw))
        runCurrent()
        assertFalse(REMOTE in client.adminSessions.value)
        current.cancel()
    }

    @Test
    fun bufferedOlderKeyDoesNotReplaceTheLaterSeedKey() = runTest {
        val device = SessionDevice(holdOwner = true, olderLocalKey = true)
        val client = buildClient(device)
        val connect = backgroundScope.async { client.connect() }
        repeat(100) {
            if (!device.ownerHeld) {
                advanceTimeBy(100)
                runCurrent()
            }
        }
        assertTrue(device.ownerHeld, "handshake must reach the owner request")
        advanceTimeBy(5_000)
        runCurrent()
        val releasedAt = Instant.fromEpochMilliseconds(currentTime)
        device.releaseOwner()
        runCurrent()
        connect.await()

        val local = assertIs<AdminSessionStatus.Active>(client.adminSessions.value[LOCAL])
        assertEquals(releasedAt, local.refreshedAt)
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private fun TestScope.buildClient(device: SessionDevice): RadioClient = RadioClient.Builder()
        .transport(device)
        .storage(InMemoryStorageProvider())
        .clock(SchedulerClock { currentTime })
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .rpcTimeout(600.seconds)
        .build()

    private fun TestScope.keepAlive(device: SessionDevice, totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0) {
            val step = minOf(10_000L, remaining)
            advanceTimeBy(step)
            runCurrent()
            device.inject(alivePacket())
            runCurrent()
            remaining -= step
        }
    }

    private fun TestScope.answerRemoteMetadata(client: RadioClient, device: SessionDevice, passkey: ByteArray) {
        val before = device.outboundPackets().size
        val call = backgroundScope.async { client.admin.forNode(REMOTE).getDeviceMetadata() }
        runCurrent()
        val request = device.outboundPackets().drop(before).last { it.to == REMOTE.raw }
        device.inject(
            adminResponse(
                requestId = request.id,
                from = REMOTE.raw,
                response = AdminMessage.Builder().also { wb ->
                    wb.get_device_metadata_response = DeviceMetadata.Builder().also { wb ->
                        wb.firmware_version = "2.7.0"
                    }.build()
                    wb.session_passkey = passkey.toByteString()
                }.build(),
            ),
        )
        runCurrent()
        assertNotNull(call.getCompleted())
    }

    private fun alivePacket(): FromRadio = FromRadio.Builder().also { wb ->
        wb.packet = MeshPacket.Builder().also { wb ->
            wb.id = 9_999
            wb.from = LOCAL.raw
            wb.to = 0
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.TEXT_MESSAGE_APP
            }.build()
        }.build()
    }.build()

    private fun routingError(requestId: Int, from: Int): FromRadio = FromRadio.Builder().also { wb ->
        wb.packet = MeshPacket.Builder().also { wb ->
            wb.from = from
            wb.to = 0
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ROUTING_APP
                wb.request_id = requestId
                wb.payload = Routing.ADAPTER.encode(
                    Routing.Builder().also { wb ->
                        wb.error_reason = Routing.Error.ADMIN_BAD_SESSION_KEY
                    }.build(),
                ).toByteString()
            }.build()
        }.build()
    }.build()

    private class SchedulerClock(private val nowMs: () -> Long) : kotlin.time.Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs())
    }

    private companion object {
        val LOCAL = NodeId(0x11111111)
        val REMOTE = NodeId(0x22222222)
        val LOCAL_PASSKEY = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val REMOTE_PASSKEY = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        val NEWER_PASSKEY = byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28)
    }

    /** Minimal radio: two-stage handshake plus a `get_owner_response` that seeds [LOCAL_PASSKEY]. */
    private class SessionDevice(private val holdOwner: Boolean = false, private val olderLocalKey: Boolean = false) :
        RadioTransport {
        private var heldOwner: FromRadio? = null

        val ownerHeld: Boolean get() = heldOwner != null

        /** Delivers a withheld `get_owner_response`. */
        fun releaseOwner() {
            heldOwner?.let(::inject)
            heldOwner = null
        }

        override val identity = TransportIdentity("fake:admin-session")
        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
        private val sent = mutableListOf<Frame>()

        override val state: StateFlow<TransportState> = stateFlow

        override suspend fun connect() {
            stateFlow.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            stateFlow.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            sent += frame
            val toRadio = decode(frame) ?: return
            when (toRadio.want_config_id) {
                NONCE_STAGE1 -> {
                    inject(
                        FromRadio.Builder().also { wb ->
                            wb.my_info = MyNodeInfo.Builder().also { wb -> wb.my_node_num = LOCAL.raw }.build()
                        }.build(),
                    )
                    inject(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE1 }.build())
                }

                NONCE_STAGE2 -> {
                    inject(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE2 }.build())
                    if (holdOwner) inject(remoteMetadataResponse())
                    if (olderLocalKey) {
                        inject(
                            adminResponse(
                                requestId = 0,
                                from = LOCAL.raw,
                                response = AdminMessage.Builder().also { wb ->
                                    wb.get_device_metadata_response = DeviceMetadata.Builder().also { wb ->
                                        wb.firmware_version = "2.7.0"
                                    }.build()
                                    wb.session_passkey = NEWER_PASSKEY.toByteString()
                                }.build(),
                            ),
                        )
                    }
                }
            }
            val packet = toRadio.packet ?: return
            val admin = packet.decoded?.payload
                ?.let { runCatching { AdminMessage.ADAPTER.decode(it) }.getOrNull() } ?: return
            if (admin.get_owner_request == true && packet.to != REMOTE.raw) {
                val response =
                    adminResponse(
                        requestId = packet.id,
                        from = LOCAL.raw,
                        response = AdminMessage.Builder().also { wb ->
                            wb.get_owner_response = User.Builder().also { wb ->
                                wb.id = "!11111111"
                                wb.long_name = "Local"
                                wb.short_name = "L"
                                wb.hw_model = HardwareModel.UNSET
                            }.build()
                            wb.session_passkey = LOCAL_PASSKEY.toByteString()
                        }.build(),
                    )
                if (holdOwner) heldOwner = response else inject(response)
            }
        }

        private fun remoteMetadataResponse(): FromRadio = adminResponse(
            requestId = 0,
            from = REMOTE.raw,
            response = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_response = DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "2.7.0"
                }.build()
                wb.session_passkey = REMOTE_PASSKEY.toByteString()
            }.build(),
        )

        override fun frames(): Flow<Frame> = inbound

        fun inject(fromRadio: FromRadio) {
            inbound.tryEmit(fromRadio.toFrame())
        }

        fun outboundPackets(): List<MeshPacket> = sent.mapNotNull { decode(it)?.packet }

        private fun decode(frame: Frame): ToRadio? {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return null
            return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
        }
    }
}

private const val NONCE_STAGE1 = 69420
private const val NONCE_STAGE2 = 69421

private fun adminResponse(requestId: Int, from: Int, response: AdminMessage): FromRadio =
    FromRadio.Builder().also { wb ->
        wb.packet = MeshPacket.Builder().also { wb ->
            wb.from = from
            wb.to = 0
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ADMIN_APP
                wb.payload = AdminMessage.ADAPTER.encode(response).toByteString()
                wb.request_id = requestId
            }.build()
        }.build()
    }.build()
