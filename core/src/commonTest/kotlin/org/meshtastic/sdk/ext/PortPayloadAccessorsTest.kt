/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.HardwareMessage
import org.meshtastic.proto.KeyVerification
import org.meshtastic.proto.MapReport
import org.meshtastic.proto.MeshBeacon
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.PowerStressMessage
import org.meshtastic.proto.RemoteShell
import org.meshtastic.proto.StatusMessage
import org.meshtastic.proto.TAKPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortPayloadAccessorsTest {
    private fun pkt(port: PortNum, payload: ByteArray) = MeshPacket.Builder().also { wb ->
        wb.decoded = Data.Builder().also { db ->
            db.portnum = port
            db.payload = payload.toByteString()
        }.build()
    }.build()

    private val garbage = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    // ── Text portnums ─────────────────────────────────────────────────────

    @Test fun alertTextDecodes() {
        assertEquals("low battery", pkt(PortNum.ALERT_APP, "low battery".encodeUtf8().toByteArray()).asAlertText())
    }

    @Test fun alertTextWrongPortReturnsNull() {
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, "x".encodeUtf8().toByteArray()).asAlertText())
    }

    @Test fun alertTextWithoutDecodedReturnsNull() {
        assertNull(MeshPacket.Builder().build().asAlertText())
    }

    @Test fun rangeTestTextDecodes() {
        assertEquals("seq 7", pkt(PortNum.RANGE_TEST_APP, "seq 7".encodeUtf8().toByteArray()).asRangeTestText())
    }

    @Test fun rangeTestTextWrongPortReturnsNull() {
        assertNull(pkt(PortNum.ALERT_APP, "x".encodeUtf8().toByteArray()).asRangeTestText())
    }

    @Test fun detectionSensorTextDecodes() {
        val text = pkt(PortNum.DETECTION_SENSOR_APP, "door open".encodeUtf8().toByteArray()).asDetectionSensorText()
        assertEquals("door open", text)
    }

    @Test fun detectionSensorTextWrongPortReturnsNull() {
        assertNull(pkt(PortNum.RANGE_TEST_APP, "x".encodeUtf8().toByteArray()).asDetectionSensorText())
    }

    // ── Protobuf portnums ─────────────────────────────────────────────────

    @Test fun paxcountDecodes() {
        val msg = Paxcount.Builder().also {
            it.wifi = 3
            it.ble = 5
            it.uptime = 60
        }.build()
        val decoded = pkt(PortNum.PAXCOUNTER_APP, Paxcount.ADAPTER.encode(msg)).asPaxcount()
        assertEquals(msg, decoded)
    }

    @Test fun paxcountWrongPortReturnsNull() {
        val msg = Paxcount.Builder().also { it.wifi = 1 }.build()
        assertNull(pkt(PortNum.TELEMETRY_APP, Paxcount.ADAPTER.encode(msg)).asPaxcount())
    }

    @Test fun paxcountGarbageReturnsNull() {
        assertNull(pkt(PortNum.PAXCOUNTER_APP, garbage).asPaxcount())
    }

    @Test fun remoteHardwareDecodes() {
        val msg = HardwareMessage.Builder().also {
            it.type = HardwareMessage.Type.WRITE_GPIOS
            it.gpio_mask = 0x10
            it.gpio_value = 0x10
        }.build()
        val decoded = pkt(PortNum.REMOTE_HARDWARE_APP, HardwareMessage.ADAPTER.encode(msg)).asRemoteHardware()
        assertEquals(msg, decoded)
    }

    @Test fun remoteHardwareWrongPortReturnsNull() {
        val msg = HardwareMessage.Builder().also { it.gpio_mask = 1 }.build()
        assertNull(pkt(PortNum.PAXCOUNTER_APP, HardwareMessage.ADAPTER.encode(msg)).asRemoteHardware())
    }

    @Test fun remoteHardwareGarbageReturnsNull() {
        assertNull(pkt(PortNum.REMOTE_HARDWARE_APP, garbage).asRemoteHardware())
    }

    @Test fun nodeStatusDecodes() {
        val msg = StatusMessage.Builder().also { it.status = "on a hike" }.build()
        val decoded = pkt(PortNum.NODE_STATUS_APP, StatusMessage.ADAPTER.encode(msg)).asNodeStatus()
        assertEquals("on a hike", decoded?.status)
    }

    @Test fun nodeStatusWrongPortReturnsNull() {
        val msg = StatusMessage.Builder().also { it.status = "x" }.build()
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, StatusMessage.ADAPTER.encode(msg)).asNodeStatus())
    }

    @Test fun nodeStatusGarbageReturnsNull() {
        assertNull(pkt(PortNum.NODE_STATUS_APP, garbage).asNodeStatus())
    }

    @Test fun mapReportDecodes() {
        val msg = MapReport.Builder().also {
            it.long_name = "Hilltop"
            it.short_name = "HT"
        }.build()
        val decoded = pkt(PortNum.MAP_REPORT_APP, MapReport.ADAPTER.encode(msg)).asMapReport()
        assertEquals("Hilltop", decoded?.long_name)
        assertEquals("HT", decoded?.short_name)
    }

    @Test fun mapReportWrongPortReturnsNull() {
        val msg = MapReport.Builder().also { it.long_name = "x" }.build()
        assertNull(pkt(PortNum.NODE_STATUS_APP, MapReport.ADAPTER.encode(msg)).asMapReport())
    }

    @Test fun mapReportGarbageReturnsNull() {
        assertNull(pkt(PortNum.MAP_REPORT_APP, garbage).asMapReport())
    }

    @Test fun meshBeaconDecodes() {
        val msg = MeshBeacon.Builder().also { it.message = "join us" }.build()
        val decoded = pkt(PortNum.MESH_BEACON_APP, MeshBeacon.ADAPTER.encode(msg)).asMeshBeacon()
        assertEquals("join us", decoded?.message)
    }

    @Test fun meshBeaconWrongPortReturnsNull() {
        val msg = MeshBeacon.Builder().also { it.message = "x" }.build()
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, MeshBeacon.ADAPTER.encode(msg)).asMeshBeacon())
    }

    @Test fun meshBeaconGarbageReturnsNull() {
        assertNull(pkt(PortNum.MESH_BEACON_APP, garbage).asMeshBeacon())
    }

    @Test fun remoteShellDecodes() {
        val msg = RemoteShell.Builder().also {
            it.op = RemoteShell.OpCode.INPUT
            it.session_id = 9
            it.payload = "ls\n".encodeUtf8()
        }.build()
        val decoded = pkt(PortNum.REMOTE_SHELL_APP, RemoteShell.ADAPTER.encode(msg)).asRemoteShell()
        assertEquals(msg, decoded)
    }

    @Test fun remoteShellWrongPortReturnsNull() {
        val msg = RemoteShell.Builder().also { it.session_id = 1 }.build()
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, RemoteShell.ADAPTER.encode(msg)).asRemoteShell())
    }

    @Test fun remoteShellGarbageReturnsNull() {
        assertNull(pkt(PortNum.REMOTE_SHELL_APP, garbage).asRemoteShell())
    }

    @Test fun powerStressDecodes() {
        val msg = PowerStressMessage.Builder().also {
            it.cmd = PowerStressMessage.Opcode.LED_ON
            it.num_seconds = 2.5f
        }.build()
        val decoded = pkt(PortNum.POWERSTRESS_APP, PowerStressMessage.ADAPTER.encode(msg)).asPowerStress()
        assertEquals(msg, decoded)
    }

    @Test fun powerStressWrongPortReturnsNull() {
        val msg = PowerStressMessage.Builder().also { it.num_seconds = 1f }.build()
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, PowerStressMessage.ADAPTER.encode(msg)).asPowerStress())
    }

    @Test fun powerStressGarbageReturnsNull() {
        assertNull(pkt(PortNum.POWERSTRESS_APP, garbage).asPowerStress())
    }

    @Test fun keyVerificationDecodes() {
        val msg = KeyVerification.Builder().also {
            it.nonce = 1234L
            it.hash1 = "abc".encodeUtf8()
        }.build()
        val decoded = pkt(PortNum.KEY_VERIFICATION_APP, KeyVerification.ADAPTER.encode(msg)).asKeyVerification()
        assertEquals(msg, decoded)
    }

    @Test fun keyVerificationWrongPortReturnsNull() {
        val msg = KeyVerification.Builder().also { it.nonce = 1L }.build()
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, KeyVerification.ADAPTER.encode(msg)).asKeyVerification())
    }

    @Test fun keyVerificationGarbageReturnsNull() {
        assertNull(pkt(PortNum.KEY_VERIFICATION_APP, garbage).asKeyVerification())
    }

    @Test fun atakPluginDecodes() {
        val msg = TAKPacket.Builder().also { it.is_compressed = true }.build()
        val decoded = pkt(PortNum.ATAK_PLUGIN, TAKPacket.ADAPTER.encode(msg)).asAtakPacket()
        assertEquals(msg, decoded)
    }

    @Test fun atakPluginWrongPortReturnsNull() {
        val msg = TAKPacket.Builder().also { it.is_compressed = true }.build()
        assertNull(pkt(PortNum.PRIVATE_APP, TAKPacket.ADAPTER.encode(msg)).asAtakPacket())
    }

    @Test fun atakPluginGarbageReturnsNull() {
        assertNull(pkt(PortNum.ATAK_PLUGIN, garbage).asAtakPacket())
    }
}
