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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/** How a paced admin write ends when the radio refuses it or its caller goes away. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminPacedWriteOutcomeTest {

    @Test
    fun aDisabledRadioIsReportedAsNoInterfaceNotAsRateLimited() = runTest {
        val (transport, client) = connected()
        val save = async { client.admin.setConfig(router) }
        runCurrent()

        transport.queueStatus(adminWrites(transport).single(), ERRNO_DISABLED)
        runCurrent()

        assertEquals(AdminResult.Failed(Routing.Error.NO_INTERFACE), save.await())
        client.disconnect()
    }

    @Test
    fun anyOtherQueueRejectionIsRateLimited() = runTest {
        val (transport, client) = connected()
        val save = async { client.admin.setConfig(router) }
        runCurrent()

        transport.queueStatus(adminWrites(transport).single(), ERRNO_UNKNOWN)
        runCurrent()

        assertEquals(AdminResult.RateLimited, save.await())
        client.disconnect()
    }

    @Test
    fun aRoutingErrorInTheQueueResultIsReportedAsThatErrorNotAsRateLimited() = runTest {
        val (transport, client) = connected()
        val save = async { client.admin.forNode(NodeId(REMOTE)).setConfig(router) }
        runCurrent()

        // Router::send returns perhapsEncode's error for a node it holds no key for, and sendToMesh reports it.
        transport.queueStatus(adminWrites(transport).single(), PKI_SEND_FAIL_PUBLIC_KEY)
        runCurrent()

        assertEquals(AdminResult.Failed(Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY), save.await())
        client.disconnect()
    }

    // Router::send returns Routing.Error codes below the ERRNO range as the queue result: DUTY_CYCLE_LIMIT (9)
    // once the hourly TX budget is spent (Router.cpp), the same rejection an RPC already maps through the table.
    @Test
    fun aDutyCycleQueueRejectionIsReportedAsThatErrorNotAsRateLimited() = runTest {
        val (transport, client) = connected()
        val save = async { client.admin.setConfig(router) }
        runCurrent()

        transport.queueStatus(adminWrites(transport).single(), DUTY_CYCLE_LIMIT)
        runCurrent()

        assertEquals(AdminResult.Failed(Routing.Error.DUTY_CYCLE_LIMIT), save.await())
        client.disconnect()
    }

    // abortSendAndNak sends the phone the same DUTY_CYCLE_LIMIT as a NAK; the link is still up.
    @Test
    fun aDutyCycleNakIsReportedAsThatErrorNotAsUnreachable() = runTest {
        val (transport, client) = connected()
        val save = async { client.admin.setConfig(router) }
        runCurrent()

        val write = adminWrites(transport).single()
        transport.queueStatus(write, 0)
        transport.injectRoutingError(write, Routing.Error.DUTY_CYCLE_LIMIT)
        runCurrent()

        assertEquals(AdminResult.Failed(Routing.Error.DUTY_CYCLE_LIMIT), save.await())
        client.disconnect()
    }

    @Test
    fun aQueuedWriteWhoseCallerIsCancelledNeverReachesTheRadio() = runTest {
        val (transport, client) = connected()
        val first = async { client.admin.setConfig(router) }
        runCurrent()
        val second = async { client.admin.setConfig(router) }
        runCurrent()
        assertEquals(1, adminWrites(transport).size, "the second write waits behind the first")

        second.cancel()
        runCurrent()
        transport.queueStatus(adminWrites(transport).single(), 0)
        runCurrent()

        assertEquals(1, adminWrites(transport).size, "the cancelled write was not sent")
        first.cancel()
        client.disconnect()
    }

    private suspend fun TestScope.connected(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(TransportIdentity("fake:admin-outcome"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        return transport to client
    }

    // The config writes only; the handshake also sends an admin get_owner request.
    private fun adminWrites(transport: FakeRadioTransport): List<Int> = transport.outboundPackets()
        .filter { p ->
            val decoded = p.decoded
            decoded?.portnum == PortNum.ADMIN_APP && AdminMessage.ADAPTER.decode(decoded.payload).set_config != null
        }
        .map { it.id }

    private fun FakeRadioTransport.queueStatus(id: Int, result: Int) {
        val status = QueueStatus.Builder().also {
            it.mesh_packet_id = id
            it.res = result
            it.free = 4
        }.build()
        injectFrame(FromRadio.Builder().also { it.queueStatus = status }.build().toFrame())
    }

    private val router = Config.Builder().also { wb ->
        wb.device = Config.DeviceConfig.Builder().also { it.role = Config.DeviceConfig.Role.ROUTER }.build()
    }.build()

    private companion object {
        const val ERRNO_DISABLED = 34

        const val DUTY_CYCLE_LIMIT = 9

        const val ERRNO_UNKNOWN = 32

        const val PKI_SEND_FAIL_PUBLIC_KEY = 39

        const val REMOTE = 0x0BAD_CAFE
    }
}
