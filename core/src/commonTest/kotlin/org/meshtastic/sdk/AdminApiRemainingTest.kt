/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")

package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeRemoteHardwarePinsResponse
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.SensorConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AdminApiRemainingTest {

    @Test
    fun setHamModeSendsHamParameters() = runTest {
        val params = HamParameters(call_sign = "KD2ABC", tx_power = 20)
        assertAckSuccess(
            call = { it.setHamMode(params) },
            requestMatches = { it.set_ham_mode == params },
        )
    }

    @Test
    fun enterDfuModeSendsFireAndForgetRequestWithoutTimingOut() = runTest {
        assertFireAndForgetSuccess(
            call = { it.enterDfuMode() },
            requestMatches = { it.enter_dfu_mode_request == true },
        )
    }

    @Test
    fun keyVerificationSendsVerificationMessage() = runTest {
        val verification = KeyVerificationAdmin(remote_nodenum = 0x01020304, nonce = 1234L)
        assertAckSuccess(
            call = { it.keyVerification(verification) },
            requestMatches = { it.key_verification == verification },
        )
    }

    @Test
    fun setSensorConfigSendsSensorConfig() = runTest {
        val config = SensorConfig()
        assertAckSuccess(
            call = { it.setSensorConfig(config) },
            requestMatches = { it.sensor_config == config },
        )
    }

    @Test
    fun sendInputEventSendsInputEvent() = runTest {
        val event = AdminMessage.InputEvent(event_code = 17, kb_char = 65)
        assertAckSuccess(
            call = { it.sendInputEvent(event) },
            requestMatches = { it.send_input_event == event },
        )
    }

    @Test
    fun addContactSendsSharedContact() = runTest {
        val contact = SharedContact(
            node_num = 77,
            user = User(id = "!0000004d", long_name = "Contact", short_name = "CT"),
        )
        assertAckSuccess(
            call = { it.addContact(contact) },
            requestMatches = { it.add_contact == contact },
        )
    }

    @Test
    fun lockdownLockNowSendsFireAndForgetToLocalNode() = runTest {
        val auth = LockdownAuth(lock_now = true)
        assertFireAndForgetSuccess(
            call = { it.lockdown(auth) },
            requestMatches = { it.lockdown_auth == auth },
        )
    }

    @Test
    fun lockdownProvisionSendsPassphraseAndDevelopFields() = runTest {
        // Exercises develop-SNAPSHOT-only fields (max_session_seconds, disable) alongside the
        // passphrase, proving the SDK compiles and round-trips the full LockdownAuth surface.
        val auth = LockdownAuth(
            passphrase = "hunter2".encodeToByteArray().toByteString(),
            boots_remaining = 10,
            valid_until_epoch = 1_900_000_000,
            max_session_seconds = 3600,
            disable = false,
        )
        assertFireAndForgetSuccess(
            call = { it.lockdown(auth) },
            requestMatches = { it.lockdown_auth == auth },
        )
    }

    @Test
    fun lockdownRejectsRemoteTargeting() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val remote = client.admin.forNode(NodeId(0x22222222))
            val result = remote.lockdown(LockdownAuth(lock_now = true))
            runCurrent()

            assertEquals(AdminResult.Unauthorized, result)
            // The passphrase must never reach the wire when targeting a remote node.
            val sentLockdown = transport.outboundPackets().drop(outboundBefore)
                .any { adminOf(it)?.lockdown_auth != null }
            assertFalse(sentLockdown, "lockdown_auth must not be sent to a remote node")
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun getRemoteHardwarePinsRequestsRemotePins() = runTest {
        val expected = NodeRemoteHardwarePinsResponse()
        assertRpcSuccess(
            call = { it.getRemoteHardwarePins() },
            requestMatches = { it.get_node_remote_hardware_pins_request == true },
            response = AdminMessage(get_node_remote_hardware_pins_response = expected),
            expected = expected,
        )
    }

    @Test
    fun getDeviceConnectionStatusRequestsConnectionStatus() = runTest {
        val expected = DeviceConnectionStatus()
        assertRpcSuccess(
            call = { it.getDeviceConnectionStatus() },
            requestMatches = { it.get_device_connection_status_request == true },
            response = AdminMessage(get_device_connection_status_response = expected),
            expected = expected,
        )
    }

    @Test
    fun deleteFileSendsDeleteRequest() = runTest {
        val path = "logs/app.txt"
        assertAckSuccess(
            call = { it.deleteFile(path) },
            requestMatches = { it.delete_file_request == path },
        )
    }

    @Test
    fun otaRequestSendsOtaEvent() = runTest {
        val event = AdminMessage.OTAEvent()
        assertAckSuccess(
            call = { it.otaRequest(event) },
            requestMatches = { it.ota_request == event },
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun rebootOtaSendsDelaySeconds() = runTest {
        assertAckSuccess(
            call = { it.rebootOta(5.seconds) },
            requestMatches = { it.reboot_ota_seconds == 5 },
        )
    }

    @Test
    fun exitSimulatorSendsExitFlag() = runTest {
        assertAckSuccess(
            call = { it.exitSimulator() },
            requestMatches = { it.exit_simulator == true },
        )
    }

    @Test
    fun setScaleSendsScaleValue() = runTest {
        assertAckSuccess(
            call = { it.setScale(240) },
            requestMatches = { it.set_scale == 240 },
        )
    }

    @Test
    fun ackWritesTimeoutForHamDeleteAndScale() = runTest {
        val params = HamParameters(call_sign = "KD2ABC", tx_power = 20)
        assertAckTimeout(
            call = { it.setHamMode(params) },
            requestMatches = { it.set_ham_mode == params },
        )
        assertAckTimeout(
            call = { it.deleteFile("logs/app.txt") },
            requestMatches = { it.delete_file_request == "logs/app.txt" },
        )
        assertAckTimeout(
            call = { it.setScale(240) },
            requestMatches = { it.set_scale == 240 },
        )
    }

    @Test
    fun ackWritesTimeoutForVerificationInputAndContact() = runTest {
        val verification = KeyVerificationAdmin(remote_nodenum = 0x01020304, nonce = 1234L)
        val event = AdminMessage.InputEvent(event_code = 17, kb_char = 65)
        val contact = SharedContact(
            node_num = 77,
            user = User(id = "!0000004d", long_name = "Contact", short_name = "CT"),
        )
        assertAckTimeout(
            call = { it.keyVerification(verification) },
            requestMatches = { it.key_verification == verification },
        )
        assertAckTimeout(
            call = { it.sendInputEvent(event) },
            requestMatches = { it.send_input_event == event },
        )
        assertAckTimeout(
            call = { it.addContact(contact) },
            requestMatches = { it.add_contact == contact },
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun ackWritesTimeoutForSensorOtaRebootAndExit() = runTest {
        val config = SensorConfig()
        val event = AdminMessage.OTAEvent()
        assertAckTimeout(
            call = { it.setSensorConfig(config) },
            requestMatches = { it.sensor_config == config },
        )
        assertAckTimeout(
            call = { it.otaRequest(event) },
            requestMatches = { it.ota_request == event },
        )
        assertAckTimeout(
            call = { it.rebootOta(5.seconds) },
            requestMatches = { it.reboot_ota_seconds == 5 },
        )
        assertAckTimeout(
            call = { it.exitSimulator() },
            requestMatches = { it.exit_simulator == true },
        )
    }

    @Test
    fun getOperationsTimeoutForRemotePinsAndConnectionStatus() = runTest {
        assertRpcTimeout(
            call = { it.getRemoteHardwarePins() },
            requestMatches = { it.get_node_remote_hardware_pins_request == true },
        )
        assertRpcTimeout(
            call = { it.getDeviceConnectionStatus() },
            requestMatches = { it.get_device_connection_status_request == true },
        )
    }

    private fun TestScope.connectedClient(rpcTimeout: Duration = 60.seconds): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:admin-remaining"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(rpcTimeout)
            .sendTimeout(rpcTimeout)
            .build()
        return transport to client
    }

    private suspend fun TestScope.assertAckSuccess(
        call: suspend (AdminApi) -> AdminResult<Unit>,
        requestMatches: (AdminMessage) -> Boolean,
    ) {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            assertTrue(packet.want_ack)
            assertEquals(false, packet.decoded?.want_response)
            assertNotNull(adminOf(packet))

            transport.injectRoutingAck(packet.id)
            runCurrent()

            when (val result = deferred.await()) {
                is AdminResult.Success -> Unit
                else -> fail("Expected success but got $result")
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun TestScope.assertAckTimeout(
        call: suspend (AdminApi) -> AdminResult<Unit>,
        requestMatches: (AdminMessage) -> Boolean,
    ) {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            assertTrue(packet.want_ack)
            assertEquals(false, packet.decoded?.want_response)
            assertNotNull(adminOf(packet))

            advanceTimeBy(70.seconds)
            runCurrent()

            assertEquals(AdminResult.Timeout, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun <T> TestScope.assertRpcSuccess(
        call: suspend (AdminApi) -> AdminResult<T>,
        requestMatches: (AdminMessage) -> Boolean,
        response: AdminMessage,
        expected: T,
    ) {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            assertFalse(packet.want_ack)
            assertEquals(true, packet.decoded?.want_response)
            assertNotNull(adminOf(packet))

            transport.injectAdminResponse(packet.id, response)
            runCurrent()

            when (val result = deferred.await()) {
                is AdminResult.Success -> assertEquals(expected, result.value)
                else -> fail("Expected success but got $result")
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun TestScope.assertRpcTimeout(
        call: suspend (AdminApi) -> AdminResult<*>,
        requestMatches: (AdminMessage) -> Boolean,
    ) {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            assertFalse(packet.want_ack)
            assertEquals(true, packet.decoded?.want_response)
            assertNotNull(adminOf(packet))

            advanceTimeBy(70.seconds)
            runCurrent()

            assertEquals(AdminResult.Timeout, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun TestScope.assertFireAndForgetSuccess(
        call: suspend (AdminApi) -> AdminResult<Unit>,
        requestMatches: (AdminMessage) -> Boolean,
    ) {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            assertFalse(packet.want_ack)
            assertEquals(false, packet.decoded?.want_response)
            assertNotNull(adminOf(packet))

            advanceTimeBy(70.seconds)
            runCurrent()

            when (val result = deferred.await()) {
                is AdminResult.Success -> Unit
                else -> fail("Expected success but got $result")
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun latestAdminPacket(
        transport: FakeRadioTransport,
        outboundBefore: Int,
        predicate: (AdminMessage) -> Boolean,
    ): MeshPacket = transport.outboundPackets().drop(outboundBefore)
        .last { packet -> adminOf(packet)?.let(predicate) == true }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }
}
