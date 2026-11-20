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
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** A device-acked `setConfig` / `setModuleConfig` lands in `configBundle` like an `editSettings` commit does. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetConfigRefreshTest {

    private val routerConfig = Config.Builder().also { wb ->
        wb.device = Config.DeviceConfig.Builder().also { wb -> wb.role = Config.DeviceConfig.Role.ROUTER }.build()
    }.build()

    private val mqttOn = ModuleConfig.Builder().also { wb ->
        wb.mqtt = ModuleConfig.MQTTConfig.Builder().also { wb -> wb.enabled = true }.build()
    }.build()

    @Test
    fun ackedSetConfigUpdatesConfigBundle() = runTest {
        val (transport, client) = connectedClient()
        try {
            val before = transport.outboundPackets().size
            val deferred = async { client.admin.setConfig(routerConfig) }
            runCurrent()
            transport.injectRoutingAck(adminPacket(transport, before) { it.set_config != null }.id)
            runCurrent()

            assertIs<AdminResult.Success<Unit>>(deferred.await())
            val bundle = assertNotNull(client.configBundle.value)
            assertEquals(Config.DeviceConfig.Role.ROUTER, bundle.configs.firstNotNullOf { it.device }.role)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun ackedSetModuleConfigUpdatesConfigBundle() = runTest {
        val (transport, client) = connectedClient()
        try {
            val before = transport.outboundPackets().size
            val deferred = async { client.admin.setModuleConfig(mqttOn) }
            runCurrent()
            transport.injectRoutingAck(adminPacket(transport, before) { it.set_module_config != null }.id)
            runCurrent()

            assertIs<AdminResult.Success<Unit>>(deferred.await())
            val bundle = assertNotNull(client.configBundle.value)
            assertEquals(true, bundle.moduleConfigs.firstNotNullOf { it.mqtt }.enabled)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun remoteNodeSetConfigLeavesLocalBundleAlone() = runTest {
        val (transport, client) = connectedClient()
        try {
            val before = transport.outboundPackets().size
            val deferred = async { client.admin.forNode(NodeId(2)).setConfig(routerConfig) }
            runCurrent()
            transport.injectRoutingAck(adminPacket(transport, before) { it.set_config != null }.id, fromNode = 2)
            runCurrent()

            assertIs<AdminResult.Success<Unit>>(deferred.await())
            val bundle = assertNotNull(client.configBundle.value)
            assertEquals(Config.DeviceConfig.Role.CLIENT, bundle.configs.firstNotNullOf { it.device }.role)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun remoteChannelWriteKeepsLocalChannels() = runTest {
        val storage = InMemoryStorage()
        val (transport, client) = connectedClient(storage)
        try {
            val localChannels = assertNotNull(client.channels.value)
            val remoteChannel = localChannels.single().newBuilder().also {
                it.settings = ChannelSettings.Builder().also { settings -> settings.name = "remote" }.build()
            }.build()
            val before = transport.outboundPackets().size
            val result = async { client.admin.forNode(NodeId(2)).setChannel(remoteChannel) }
            runCurrent()
            val packet = adminPacket(transport, before) { it.set_channel == remoteChannel }
            assertEquals(2, packet.to)
            transport.injectRoutingAck(packet.id, fromNode = 2)
            assertIs<AdminResult.Success<Unit>>(result.await())
            runCurrent()

            assertEquals(localChannels, client.channels.value)
            assertEquals(localChannels, storage.loadChannels())
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun remoteEditSettingsKeepsLocalConfig() = runTest {
        assertRemoteTransactionKeepsLocalConfig { admin ->
            val result = admin.editSettings {
                setConfig(routerConfig)
                setModuleConfig(mqttOn)
            }
            assertIs<AdminResult.Success<Unit>>(result)
        }
    }

    @Test
    fun remoteBatchKeepsLocalConfig() = runTest {
        assertRemoteTransactionKeepsLocalConfig { admin ->
            admin.batch {
                setConfig(routerConfig)
                setModuleConfig(mqttOn)
            }
        }
    }

    private suspend fun TestScope.assertRemoteTransactionKeepsLocalConfig(transaction: suspend (AdminApi) -> Unit) {
        val storage = InMemoryStorage()
        val (transport, client) = connectedClient(storage)
        try {
            val localConfig = assertNotNull(client.configBundle.value)
            val before = transport.outboundPackets().size
            val operation = async { transaction(client.admin.forNode(NodeId(2))) }
            runCurrent()
            val begin = adminPacket(transport, before) { it.begin_edit_settings == true }
            assertEquals(2, begin.to)
            transport.injectRoutingAck(begin.id, fromNode = 2)
            runCurrent()
            val commit = adminPacket(transport, before) { it.commit_edit_settings == true }
            assertEquals(2, commit.to)
            transport.injectRoutingAck(commit.id, fromNode = 2)
            operation.await()
            runCurrent()

            assertEquals(localConfig, client.configBundle.value)
            assertEquals(localConfig, storage.loadConfig())
        } finally {
            client.disconnect()
        }
    }

    private suspend fun TestScope.connectedClient(
        storage: InMemoryStorage = InMemoryStorage(),
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:set-config-refresh"),
            frames = listOf(
                FromRadio.Builder().also { wb ->
                    wb.channel = Channel.Builder().also { it.role = Channel.Role.PRIMARY }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.metadata = DeviceMetadata.Builder().also { wb -> wb.firmware_version = "2.7.0" }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.device = Config.DeviceConfig.Builder().also { wb ->
                            wb.role = Config.DeviceConfig.Role.CLIENT
                        }.build()
                    }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.moduleConfig = ModuleConfig.Builder().also { wb ->
                        wb.mqtt = ModuleConfig.MQTTConfig.Builder().also { wb -> wb.enabled = false }.build()
                    }.build()
                }.build().toFrame(),
            ),
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(object : StorageProvider {
                override suspend fun activate(identity: TransportIdentity): DeviceStorage = storage
            })
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)
        return transport to client
    }

    private fun adminPacket(transport: FakeRadioTransport, before: Int, match: (AdminMessage) -> Boolean): MeshPacket =
        transport.outboundPackets().drop(before).last { packet ->
            val decoded = packet.decoded
            decoded != null &&
                decoded.portnum == PortNum.ADMIN_APP &&
                runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()?.let(match) == true
        }
}
