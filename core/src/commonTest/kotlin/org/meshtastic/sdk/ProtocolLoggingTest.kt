/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ClientNotification
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.DisplayFrame
import org.meshtastic.proto.DisplayPalette
import org.meshtastic.proto.FileInfo
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.LoRaRegionPresetMap
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.LogRecord
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.XModem
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolLoggingTest {

    private class Recorded(val level: LogLevel, val tag: String, val message: String)

    private class RecordingSink : LogSink {
        val entries = mutableListOf<Recorded>()

        override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
            entries += Recorded(level, tag, message)
        }
    }

    private fun textPacket(secret: String): FromRadio = FromRadio.Builder().also { wb ->
        wb.packet = MeshPacket.Builder().also { pb ->
            pb.from = 7
            pb.to = 9
            pb.id = 1234
            pb.decoded = Data.Builder().also { db ->
                db.portnum = PortNum.TEXT_MESSAGE_APP
                db.payload = secret.encodeUtf8()
            }.build()
        }.build()
    }.build()

    private suspend fun TestScope.run(sink: RecordingSink, configure: RadioClient.Builder.() -> Unit): RadioClient {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:protocol-log"),
            frames = listOf(textPacket("correct horse battery staple").toFrame()),
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .logger(sink)
            .apply(configure)
            .build()
        client.connect()
        runCurrent()
        return client
    }

    @Test
    fun offByDefaultLogsNoProtocolLines() = runTest {
        val sink = RecordingSink()
        val client = run(sink) {}
        client.disconnect()
        assertTrue(sink.entries.none { it.tag == "Protocol" })
    }

    @Test
    fun defaultRedactorLogsRoutingMetadataButNeverPayloadText() = runTest {
        val sink = RecordingSink()
        val client = run(sink) { protocolLogging(LogLevel.DEBUG) }
        client.disconnect()
        val protocol = sink.entries.filter { it.tag == "Protocol" }
        assertTrue(protocol.any { it.message.startsWith("rx FromRadio") && "variant=packet" in it.message })
        assertTrue(protocol.any { it.message.startsWith("tx ToRadio") })
        assertTrue(protocol.all { it.level == LogLevel.DEBUG })
        assertFalse(protocol.any { "correct horse" in it.message })
        val packetLine = protocol.first { "from=7 to=9 packet_id=1234" in it.message }
        assertEquals(true, "payload_bytes=28" in packetLine.message)
    }

    @Test
    fun customRedactorIsApplied() = runTest {
        val sink = RecordingSink()
        val custom = object : PayloadRedactor {
            override fun describe(fromRadio: FromRadio): String = "CUSTOM-RX"
        }
        val client = run(sink) { protocolLogging(LogLevel.INFO, custom) }
        client.disconnect()
        assertTrue(sink.entries.any { it.message == "rx CUSTOM-RX" && it.level == LogLevel.INFO })
    }

    @Test
    fun queueStatusLineCarriesFreeMaxlenResAndPacketId() {
        val line = PayloadRedactor.Default.describe(
            FromRadio.Builder().also {
                it.queueStatus = QueueStatus.Builder().also { q ->
                    q.free = 13
                    q.maxlen = 16
                    q.res = 5
                    q.mesh_packet_id = 777
                }.build()
            }.build(),
        )
        assertTrue("variant=queueStatus" in line)
        assertTrue("free=13 maxlen=16 res=5 mesh_packet_id=777" in line)
    }

    @Test
    fun heartbeatLineCarriesNonce() {
        val line = PayloadRedactor.Default.describe(
            ToRadio.Builder().also { it.heartbeat = Heartbeat.Builder().also { h -> h.nonce = 4242 }.build() }.build(),
        )
        assertTrue("variant=heartbeat" in line)
        assertTrue("nonce=4242" in line)
    }

    @Test
    fun xmodemLineCarriesControlSeqAndLengthButNotBytes() {
        val xmodem = XModem.Builder().also {
            it.control = XModem.Control.SOH
            it.seq = 9
            it.buffer = "hunter2-file-contents".encodeUtf8()
        }.build()
        val lines = listOf(
            PayloadRedactor.Default.describe(FromRadio.Builder().also { it.xmodemPacket = xmodem }.build()),
            PayloadRedactor.Default.describe(ToRadio.Builder().also { it.xmodemPacket = xmodem }.build()),
        )
        for (line in lines) {
            assertTrue("control=SOH seq=9 buffer_bytes=21" in line)
            assertFalse("hunter2" in line)
        }
    }

    @Test
    fun configAndModuleConfigNameTheSectionOnly() {
        val config = FromRadio.Builder().also {
            it.config =
                Config.Builder().also { c ->
                    c.lora = Config.LoRaConfig.Builder().also { l -> l.hop_limit = 6 }.build()
                }
                    .build()
        }.build()
        val module = FromRadio.Builder().also {
            it.moduleConfig = ModuleConfig.Builder().also { m ->
                m.mqtt = ModuleConfig.MQTTConfig.Builder().also { q -> q.password = "s3cret" }.build()
            }.build()
        }.build()
        val configLine = PayloadRedactor.Default.describe(config)
        val moduleLine = PayloadRedactor.Default.describe(module)
        assertTrue("variant=config=lora" in configLine)
        assertTrue("variant=moduleConfig=mqtt" in moduleLine)
        assertFalse("s3cret" in moduleLine)
    }

    @Test
    fun everyEnvelopeArmIsNamed() {
        val fromRadio = listOf(
            FromRadio.Builder().also { it.packet = MeshPacket.Builder().build() }.build(),
            FromRadio.Builder().also { it.my_info = MyNodeInfo.Builder().build() }.build(),
            FromRadio.Builder().also { it.node_info = NodeInfo.Builder().build() }.build(),
            FromRadio.Builder().also {
                it.config =
                    Config.Builder().also { c -> c.device = Config.DeviceConfig.Builder().build() }.build()
            }.build(),
            FromRadio.Builder().also { it.log_record = LogRecord.Builder().build() }.build(),
            FromRadio.Builder().also { it.config_complete_id = 1 }.build(),
            FromRadio.Builder().also { it.rebooted = true }.build(),
            FromRadio.Builder().also {
                it.moduleConfig =
                    ModuleConfig.Builder().also { m -> m.audio = ModuleConfig.AudioConfig.Builder().build() }.build()
            }.build(),
            FromRadio.Builder().also { it.channel = Channel.Builder().build() }.build(),
            FromRadio.Builder().also { it.queueStatus = QueueStatus.Builder().build() }.build(),
            FromRadio.Builder().also { it.xmodemPacket = XModem.Builder().build() }.build(),
            FromRadio.Builder().also { it.metadata = DeviceMetadata.Builder().build() }.build(),
            FromRadio.Builder().also { it.mqttClientProxyMessage = MqttClientProxyMessage.Builder().build() }.build(),
            FromRadio.Builder().also { it.fileInfo = FileInfo.Builder().build() }.build(),
            FromRadio.Builder().also { it.clientNotification = ClientNotification.Builder().build() }.build(),
            FromRadio.Builder().also { it.deviceuiConfig = DeviceUIConfig.Builder().build() }.build(),
            FromRadio.Builder().also { it.lockdown_status = LockdownStatus.Builder().build() }.build(),
            FromRadio.Builder().also { it.region_presets = LoRaRegionPresetMap.Builder().build() }.build(),
            FromRadio.Builder().also { it.display_frame = DisplayFrame.Builder().build() }.build(),
            FromRadio.Builder().also { it.display_palette = DisplayPalette.Builder().build() }.build(),
        )
        for (message in fromRadio) {
            assertFalse("variant=other" in PayloadRedactor.Default.describe(message), "FromRadio arm unnamed")
        }
        val toRadio = listOf(
            ToRadio.Builder().also { it.packet = MeshPacket.Builder().build() }.build(),
            ToRadio.Builder().also { it.want_config_id = 1 }.build(),
            ToRadio.Builder().also { it.disconnect = true }.build(),
            ToRadio.Builder().also { it.xmodemPacket = XModem.Builder().build() }.build(),
            ToRadio.Builder().also { it.mqttClientProxyMessage = MqttClientProxyMessage.Builder().build() }.build(),
            ToRadio.Builder().also { it.heartbeat = Heartbeat.Builder().build() }.build(),
        )
        for (message in toRadio) {
            assertFalse("variant=other" in PayloadRedactor.Default.describe(message), "ToRadio arm unnamed")
        }
    }
}
