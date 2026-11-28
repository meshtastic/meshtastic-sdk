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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.DisplayFrame
import org.meshtastic.proto.DisplayPalette
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayMirrorTest {

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:display-mirror"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .sendAckTimeout(60.seconds)
            .build()
        return transport to client
    }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }

    private fun chunkFrame(frameId: Int, offset: Int, data: ByteArray, total: Int): Frame =
        FromRadio.Builder().also { wb ->
            wb.display_frame = DisplayFrame.Builder().also { wb ->
                wb.width = 128
                wb.height = 64
                wb.format = DisplayFrame.Format.MONO_VLSB
                wb.frame_id = frameId
                wb.offset = offset
                wb.total_size = total
                wb.data_ = data.toByteString()
            }.build()
        }.build().toFrame()

    private fun rectChunk(frameId: Int, rx: Int, ry: Int, rw: Int, rh: Int, fill: Int): Frame =
        FromRadio.Builder().also { wb ->
            wb.display_frame = DisplayFrame.Builder().also { wb ->
                wb.width = 8
                wb.height = 4
                wb.format = DisplayFrame.Format.RGB565
                wb.frame_id = frameId
                wb.offset = 0
                wb.total_size = rw * rh * 2
                wb.data_ = ByteArray(rw * rh * 2) { fill.toByte() }.toByteString()
                wb.rect_x = rx
                wb.rect_y = ry
                wb.rect_width = rw
                wb.rect_height = rh
            }.build()
        }.build().toFrame()

    private suspend fun TestScope.assertAcked(
        call: suspend (AdminApi) -> AdminResult<Unit>,
        matches: (AdminMessage) -> Boolean,
    ) {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val before = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()
            val packet = transport.outboundPackets().drop(before).last { adminOf(it)?.let(matches) == true }
            assertTrue(packet.want_ack)
            transport.injectRoutingAck(packet.id)
            runCurrent()
            assertEquals(AdminResult.Success(Unit), deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun setDisplayMirrorEnableSendsTrue() = runTest {
        assertAcked({ it.setDisplayMirror(true) }, { it.set_display_mirror == true })
    }

    @Test
    fun setDisplayMirrorDisableSendsFalse() = runTest {
        assertAcked({ it.setDisplayMirror(false) }, { it.set_display_mirror == false })
    }

    @Test
    fun requestDisplayFrameSendsRequest() = runTest {
        assertAcked({ it.requestDisplayFrame() }, { it.get_display_frame_request == true })
    }

    @Test
    fun remoteScopedMirrorCallsReturnUnauthorizedAndSendNothing() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val before = transport.outboundPackets().size
            val remote = client.admin.forNode(NodeId(0x22222222))
            assertEquals(AdminResult.Unauthorized, remote.setDisplayMirror(true))
            assertEquals(AdminResult.Unauthorized, remote.requestDisplayFrame())
            runCurrent()
            val sent = transport.outboundPackets().drop(before).mapNotNull(::adminOf)
            assertFalse(sent.any { it.set_display_mirror != null || it.get_display_frame_request != null })
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun injectedChunksProduceOneCompleteFrame() = runTest {
        val (transport, client) = connectedClient()
        val frames = mutableListOf<DisplayMirrorFrame>()
        val scopedFrames = mutableListOf<SessionScoped<DisplayMirrorFrame>>()
        backgroundScope.launch {
            client.displayFrames.collect {
                scopedFrames.add(it)
                frames.add(it.value)
            }
        }
        client.connect()
        runCurrent()
        try {
            val data = ByteArray(10) { it.toByte() }
            transport.injectFrame(chunkFrame(3, 0, data.copyOfRange(0, 6), 10))
            runCurrent()
            assertTrue(frames.isEmpty())
            transport.injectFrame(chunkFrame(3, 6, data.copyOfRange(6, 10), 10))
            runCurrent()
            val frame = frames.single()
            assertEquals(data.toByteString(), frame.data)
            assertEquals(128, frame.width)
            assertEquals(3, frame.frameId)
            // Regression: display frames stamped with session 0 instead of the first handshake's id.
            assertEquals(1L, scopedFrames.single().sessionId)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun gappedChunksProduceNoFrame() = runTest {
        val (transport, client) = connectedClient()
        val frames = mutableListOf<DisplayMirrorFrame>()
        backgroundScope.launch { client.displayFrames.collect { frames.add(it.value) } }
        client.connect()
        runCurrent()
        try {
            val data = ByteArray(10)
            transport.injectFrame(chunkFrame(3, 0, data.copyOfRange(0, 4), 10))
            transport.injectFrame(chunkFrame(3, 6, data.copyOfRange(6, 10), 10))
            runCurrent()
            assertTrue(frames.isEmpty())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun displayFrameDoesNotRaiseProtocolWarning() = runTest {
        val (transport, client) = connectedClient()
        val events = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { events.add(it) } }
        client.connect()
        runCurrent()
        try {
            transport.injectFrame(chunkFrame(1, 0, ByteArray(4), 4))
            runCurrent()
            assertTrue(events.none { it is MeshEvent.ProtocolWarning })
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun paletteChunksSurfaceAsWholePalette() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            assertNull(client.displayPalette.value)
            val region = DisplayPalette.ColorRegion.Builder().also { wb -> wb.width = 4 }.build()
            transport.injectFrame(
                FromRadio.Builder().also { wb ->
                    wb.display_palette = DisplayPalette.Builder().also { wb ->
                        wb.signature = 9
                        wb.region_offset = 0
                        wb.region_total = 1
                        wb.regions = listOf(region)
                    }.build()
                }.build().toFrame(),
            )
            runCurrent()
            val palette = assertNotNull(client.displayPalette.value)
            assertEquals(9, palette.signature)
            assertEquals(1, palette.regions.size)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun disconnectClearsPalette() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.display_palette = DisplayPalette.Builder().also { wb ->
                    wb.signature = 9
                    wb.region_total = 0
                }.build()
            }.build().toFrame(),
        )
        runCurrent()
        assertNotNull(client.displayPalette.value)
        client.disconnect()
        runCurrent()
        assertNull(client.displayPalette.value)
    }

    // Regression: engine not feeding the compositor, or not clearing the image when the session ends.
    @Test
    fun displayImageCompositesRectsAndClearsWhenTheSessionEnds() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        assertNull(client.displayImage.value)
        transport.injectFrame(rectChunk(1, 0, 0, 2, 1, 0x11))
        transport.injectFrame(rectChunk(2, 4, 2, 2, 1, 0x22))
        runCurrent()
        val image = assertNotNull(client.displayImage.value)
        val bytes = image.pixels.toByteArray()
        assertEquals(2, image.frameId)
        assertEquals(8 * 4 * 2, bytes.size)
        assertTrue((0 until 4).all { bytes[it] == 0x11.toByte() })
        assertTrue(((2 * 8 + 4) * 2 until (2 * 8 + 4) * 2 + 4).all { bytes[it] == 0x22.toByte() })
        client.disconnect()
        runCurrent()
        assertNull(client.displayImage.value)
    }

    // Regression: a dropped frame clearing or replacing the current image.
    @Test
    fun malformedFrameKeepsThePreviousDisplayImage() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            transport.injectFrame(rectChunk(1, 0, 0, 2, 1, 0x11))
            runCurrent()
            val first = assertNotNull(client.displayImage.value)
            transport.injectFrame(rectChunk(2, 0, 0, 9, 1, 0x22))
            runCurrent()
            assertEquals(first, client.displayImage.value)
        } finally {
            runCatching { client.disconnect() }
        }
    }
}
