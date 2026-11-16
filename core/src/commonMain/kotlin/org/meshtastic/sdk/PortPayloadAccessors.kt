/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

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

/**
 * Decodes the payload as a UTF-8 string if [MeshPacket.decoded.portnum] matches [PortNum.ALERT_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload is empty.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asAlertText(): String? = payloadOrNull(PortNum.ALERT_APP)?.utf8()

/**
 * Decodes the payload as a UTF-8 string if [MeshPacket.decoded.portnum] matches [PortNum.RANGE_TEST_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload is empty.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asRangeTestText(): String? = payloadOrNull(PortNum.RANGE_TEST_APP)?.utf8()

/**
 * Decodes the payload as a UTF-8 string if [MeshPacket.decoded.portnum] matches
 * [PortNum.DETECTION_SENSOR_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload is empty.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asDetectionSensorText(): String? = payloadOrNull(PortNum.DETECTION_SENSOR_APP)?.utf8()

/**
 * Decodes the payload as [Paxcount] if [MeshPacket.decoded.portnum] matches [PortNum.PAXCOUNTER_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asPaxcount(): Paxcount? = decodeIfPort(PortNum.PAXCOUNTER_APP, Paxcount.ADAPTER)

/**
 * Decodes the payload as [HardwareMessage] if [MeshPacket.decoded.portnum] matches
 * [PortNum.REMOTE_HARDWARE_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asRemoteHardware(): HardwareMessage? =
    decodeIfPort(PortNum.REMOTE_HARDWARE_APP, HardwareMessage.ADAPTER)

/**
 * Decodes the payload as [StatusMessage] if [MeshPacket.decoded.portnum] matches
 * [PortNum.NODE_STATUS_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asNodeStatus(): StatusMessage? = decodeIfPort(PortNum.NODE_STATUS_APP, StatusMessage.ADAPTER)

/**
 * Decodes the payload as [MapReport] if [MeshPacket.decoded.portnum] matches [PortNum.MAP_REPORT_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asMapReport(): MapReport? = decodeIfPort(PortNum.MAP_REPORT_APP, MapReport.ADAPTER)

/**
 * Decodes the payload as [MeshBeacon] if [MeshPacket.decoded.portnum] matches [PortNum.MESH_BEACON_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asMeshBeacon(): MeshBeacon? = decodeIfPort(PortNum.MESH_BEACON_APP, MeshBeacon.ADAPTER)

/**
 * Decodes the payload as [RemoteShell] if [MeshPacket.decoded.portnum] matches [PortNum.REMOTE_SHELL_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asRemoteShell(): RemoteShell? = decodeIfPort(PortNum.REMOTE_SHELL_APP, RemoteShell.ADAPTER)

/**
 * Decodes the payload as [PowerStressMessage] if [MeshPacket.decoded.portnum] matches
 * [PortNum.POWERSTRESS_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asPowerStress(): PowerStressMessage? =
    decodeIfPort(PortNum.POWERSTRESS_APP, PowerStressMessage.ADAPTER)

/**
 * Decodes the payload as [KeyVerification] if [MeshPacket.decoded.portnum] matches
 * [PortNum.KEY_VERIFICATION_APP].
 *
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asKeyVerification(): KeyVerification? =
    decodeIfPort(PortNum.KEY_VERIFICATION_APP, KeyVerification.ADAPTER)

/**
 * Decodes the payload as [TAKPacket] if [MeshPacket.decoded.portnum] matches [PortNum.ATAK_PLUGIN].
 *
 * The V2 port ([PortNum.ATAK_PLUGIN_V2]) carries zstd-compressed `TAKPacketV2` and has no accessor.
 * Returns `null` when the packet has another portnum, has no `decoded`, or the payload fails to decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.asAtakPacket(): TAKPacket? = decodeIfPort(PortNum.ATAK_PLUGIN, TAKPacket.ADAPTER)
