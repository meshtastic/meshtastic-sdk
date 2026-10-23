/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiveNodeMergeTest {

    private val me = 0x11111111
    private val peer = 0x22222222
    private val now = 1_800_000_000L
    private val peerKey = ByteArray(32) { 7 }.toByteString()
    private val myKey = ByteArray(32) { 9 }.toByteString()

    private val known = NodeInfo.Builder().also { wb ->
        wb.num = peer
        wb.user = User.Builder().also { wb ->
            wb.id = "!22222222"
            wb.long_name = "Alpha"
            wb.short_name = "AL"
            wb.public_key = peerKey
        }.build()
        wb.position = Position.Builder().also { wb ->
            wb.latitude_i = 417_000_000
            wb.longitude_i = 447_000_000
            wb.time = 1_799_000_000
        }.build()
        wb.snr = 5f
        wb.last_heard = 1_799_000_000
        wb.hops_away = 2
    }.build()

    private fun packet(
        port: PortNum,
        payload: ByteString = ByteString.EMPTY,
        from: Int = peer,
        configure: MeshPacket.Builder.() -> Unit = {},
    ): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.from = from
        wb.to = -1
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = port
            wb.payload = payload
        }.build()
        wb.configure()
    }.build()

    private fun positionPayload(latI: Int, lonI: Int, time: Int = 0): ByteString = Position.ADAPTER.encode(
        Position.Builder().also { wb ->
            wb.latitude_i = latI
            wb.longitude_i = lonI
            wb.time = time
        }.build(),
    ).toByteString()

    private fun userPayload(longName: String, key: ByteString = ByteString.EMPTY): ByteString = User.ADAPTER.encode(
        User.Builder().also { wb ->
            wb.id = "!spoofed"
            wb.long_name = longName
            wb.short_name = longName.take(2)
            wb.public_key = key
        }.build(),
    ).toByteString()

    private fun merge(existing: NodeInfo?, packet: MeshPacket) =
        mergeLivePacket(existing, packet, myNodeNum = me, ownPublicKey = myKey, nowEpochSeconds = now)

    @Test
    fun `any packet refreshes last heard and signal and hops`() {
        val merged = merge(
            known,
            packet(PortNum.TEXT_MESSAGE_APP) {
                rx_time = 1_800_000_100
                rx_snr = -7.25f
                rx_rssi = -110
                hop_start = 3
                hop_limit = 2
            },
        )
        assertNotNull(merged)
        assertEquals(1_800_000_100, merged.last_heard)
        assertEquals(-7.25f, merged.snr)
        assertEquals(1, merged.hops_away)
        assertEquals(known.position, merged.position)
        assertEquals(known.user, merged.user)
    }

    @Test
    fun `last heard falls back to the host clock without rx_time`() {
        val merged = assertNotNull(merge(known, packet(PortNum.TEXT_MESSAGE_APP)))
        assertEquals(now.toInt(), merged.last_heard)
    }

    @Test
    fun `snr is kept when the radio did not measure the packet`() {
        val viaMqtt = merge(
            known,
            packet(PortNum.TEXT_MESSAGE_APP) {
                rx_snr = 3f
                transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_MQTT
                via_mqtt = true
            },
        )
        assertNotNull(viaMqtt)
        assertEquals(5f, viaMqtt.snr)
        assertEquals(true, viaMqtt.via_mqtt)
    }

    @Test
    fun `hops stay unknown without hop_start or with a raised limit`() {
        assertEquals(2, merge(known, packet(PortNum.TEXT_MESSAGE_APP))?.hops_away)
        val tampered = merge(
            known,
            packet(PortNum.TEXT_MESSAGE_APP) {
                hop_start = 2
                hop_limit = 5
            },
        )
        assertEquals(2, tampered?.hops_away)
    }

    @Test
    fun `position report replaces the position and keeps a known time`() {
        val merged =
            assertNotNull(merge(known, packet(PortNum.POSITION_APP, positionPayload(417_100_000, 447_200_000))))
        assertEquals(417_100_000, merged.position?.latitude_i)
        assertEquals(447_200_000, merged.position?.longitude_i)
        assertEquals(1_799_000_000, merged.position?.time)

        val timed = assertNotNull(
            merge(known, packet(PortNum.POSITION_APP, positionPayload(417_100_000, 447_200_000, time = 1_800_000_050))),
        )
        assertEquals(1_800_000_050, timed.position?.time)
    }

    @Test
    fun `node info with the same key updates the user and the id and the channel`() {
        val merged = assertNotNull(
            merge(known, packet(PortNum.NODEINFO_APP, userPayload("Alpha 2", peerKey)) { channel = 2 }),
        )
        assertEquals("Alpha 2", merged.user?.long_name)
        assertEquals("!22222222", merged.user?.id)
        assertEquals(2, merged.channel)
    }

    @Test
    fun `node info that changes or drops a known key is refused`() {
        val otherKey = ByteArray(32) { 1 }.toByteString()
        val changedKey = merge(known, packet(PortNum.NODEINFO_APP, userPayload("Mallory", otherKey)))
        assertEquals("Alpha", changedKey?.user?.long_name)
        val noKey = merge(known, packet(PortNum.NODEINFO_APP, userPayload("Mallory")))
        assertEquals(peerKey, noKey?.user?.public_key)
    }

    @Test
    fun `node info advertising our own key is refused`() {
        assertNull(merge(null, packet(PortNum.NODEINFO_APP, userPayload("Clone", myKey))))
    }

    @Test
    fun `node info adds an unknown node but other packets do not`() {
        val added = assertNotNull(
            merge(
                null,
                packet(PortNum.NODEINFO_APP, userPayload("Bravo")) {
                    rx_time =
                        1_800_000_200
                },
            ),
        )
        assertEquals(peer, added.num)
        assertEquals("Bravo", added.user?.long_name)
        assertEquals(1_800_000_200, added.last_heard)

        assertNull(merge(null, packet(PortNum.POSITION_APP, positionPayload(1, 1))))
        assertNull(merge(null, packet(PortNum.TEXT_MESSAGE_APP)))
    }

    @Test
    fun `packets from the local node leave last heard and signal alone`() {
        val self = known.newBuilder().also { wb -> wb.num = me }.build()
        assertNull(
            merge(
                self,
                packet(PortNum.TEXT_MESSAGE_APP, from = me) {
                    rx_time = 1_800_000_300
                    rx_rssi = -50
                },
            ),
        )
        val moved = assertNotNull(merge(self, packet(PortNum.POSITION_APP, positionPayload(1, 2), from = me)))
        assertEquals(1, moved.position?.latitude_i)
        assertEquals(self.last_heard, moved.last_heard)
    }

    @Test
    fun `undecodable payloads and encrypted packets change nothing but presence`() {
        val garbage = ByteArray(3) { -1 }.toByteString()
        val merged = assertNotNull(merge(known, packet(PortNum.POSITION_APP, garbage) { rx_time = 1_800_000_400 }))
        assertEquals(known.position, merged.position)
        assertEquals(1_800_000_400, merged.last_heard)

        val encrypted = MeshPacket.Builder().also { wb -> wb.from = peer }.build()
        assertNull(merge(known, encrypted))
    }
}
