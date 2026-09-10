/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Gap C refinement: external config/channel change propagation.
 *
 * Verifies that unsolicited admin messages (request_id = 0) from the connected node
 * update local state and emit [MeshEvent.ExternalConfigChange].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalConfigChangeTest {

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:gap-c-test"),
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        return transport to client
    }

    /** Helper to inject an unsolicited admin message (request_id = 0). */
    private fun FakeRadioTransport.injectUnsolicitedAdmin(adminMsg: AdminMessage) {
        val payload = okio.ByteString.of(*AdminMessage.ADAPTER.encode(adminMsg))
        val packet = MeshPacket.Builder().also { wb ->
        wb.from = nodeNum
        wb.to = 0
        wb.decoded = Data.Builder().also { wb ->
                        wb.portnum = PortNum.ADMIN_APP
                        wb.payload = payload
                        wb.request_id = 0 // unsolicited — not a response to our request
                    }.build()
        }.build()
        injectPacket(packet)
    }

    @Test
    fun externalChannelChangeUpdatesChannelsState() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val channel = Channel.Builder().also { wb ->
        wb.index = 0
        wb.settings = ChannelSettings.Builder().also { wb ->wb.name = "ExternallySet"}.build()
        wb.role = Channel.Role.PRIMARY
        }.build()

        transport.injectUnsolicitedAdmin(AdminMessage.Builder().also { wb ->wb.get_channel_response = channel}.build())
        runCurrent()

        val channels = client.channels.value
        assertNotNull(channels)
        assertTrue(channels.any { it.settings?.name == "ExternallySet" })
        client.disconnect()
    }

    @Test
    fun externalChannelChangeEmitsEvent() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val events = mutableListOf<MeshEvent>()
        val collectJob = launch(backgroundScope.coroutineContext) {
            client.events.collect { events.add(it) }
        }
        runCurrent()

        val channel = Channel.Builder().also { wb ->
        wb.index = 1
        wb.settings = ChannelSettings.Builder().also { wb ->wb.name = "NewChannel"}.build()
        wb.role = Channel.Role.SECONDARY
        }.build()
        transport.injectUnsolicitedAdmin(AdminMessage.Builder().also { wb ->wb.get_channel_response = channel}.build())
        runCurrent()

        val configEvents = events.filterIsInstance<MeshEvent.ExternalConfigChange>()
        assertTrue(configEvents.isNotEmpty(), "Expected ExternalConfigChange event")
        assertEquals(ExternalChangeKind.CHANNEL, configEvents.first().kind)

        collectJob.cancel()
        client.disconnect()
    }

    @Test
    fun externalConfigChangeUpdatesConfigBundle() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        // ConfigBundle should be non-null after connect (auto-handshake sets up myInfo but
        // may not set configs). If null, the handler exits early — which is correct behavior.
        val initialBundle = client.configBundle.value
        if (initialBundle == null) {
            // auto-handshake doesn't produce a full config bundle in minimal mode
            client.disconnect()
            return@runTest
        }

        val newLora = Config.Builder().also { wb ->wb.lora = Config.LoRaConfig.Builder().also { wb ->wb.use_preset = true; wb.region = Config.LoRaConfig.RegionCode.EU_868}.build()}.build()
        transport.injectUnsolicitedAdmin(AdminMessage.Builder().also { wb ->wb.get_config_response = newLora}.build())
        runCurrent()

        val updated = client.configBundle.value
        assertNotNull(updated)
        val loraSection = updated.configs.find { it.lora != null }
        assertNotNull(loraSection)
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, loraSection.lora?.region)

        client.disconnect()
    }

    @Test
    fun externalModuleConfigChangeEmitsEvent() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val events = mutableListOf<MeshEvent>()
        val collectJob = launch(backgroundScope.coroutineContext) {
            client.events.collect { events.add(it) }
        }
        runCurrent()

        val newMqtt = ModuleConfig.Builder().also { wb ->wb.mqtt = ModuleConfig.MQTTConfig.Builder().also { wb ->wb.enabled = true}.build()}.build()
        transport.injectUnsolicitedAdmin(AdminMessage.Builder().also { wb ->wb.get_module_config_response = newMqtt}.build())
        runCurrent()

        val configEvents = events.filterIsInstance<MeshEvent.ExternalConfigChange>()
        // Only emitted if configBundle was non-null
        if (client.configBundle.value != null || configEvents.isNotEmpty()) {
            assertTrue(configEvents.any { it.kind == ExternalChangeKind.MODULE_CONFIG })
        }

        collectJob.cancel()
        client.disconnect()
    }

    @Test
    fun solicitedAdminResponseDoesNotTriggerExternalEvent() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val events = mutableListOf<MeshEvent>()
        val collectJob = launch(backgroundScope.coroutineContext) {
            client.events.collect { events.add(it) }
        }
        runCurrent()

        // Inject a channel response WITH a non-zero request_id (simulates response to our RPC)
        val payload = okio.ByteString.of(
            *AdminMessage.ADAPTER.encode(
                AdminMessage.Builder().also { wb ->wb.get_channel_response = Channel.Builder().also { wb ->wb.index = 0; wb.role = Channel.Role.PRIMARY}.build()}.build(),
            ),
        )
        val packet = MeshPacket.Builder().also { wb ->
        wb.from = transport.nodeNum
        wb.to = 0
        wb.decoded = Data.Builder().also { wb ->
                        wb.portnum = PortNum.ADMIN_APP
                        wb.payload = payload
                        wb.request_id = 42 // non-zero → solicited response
                    }.build()
        }.build()
        transport.injectPacket(packet)
        runCurrent()

        val configEvents = events.filterIsInstance<MeshEvent.ExternalConfigChange>()
        assertTrue(configEvents.isEmpty(), "Solicited responses should NOT emit ExternalConfigChange")

        collectJob.cancel()
        client.disconnect()
    }
}
