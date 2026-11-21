/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeRadioTransportQueueStatusTest {

    @Test
    fun autoQueueStatusAnswersPacketsWithoutAutoHandshake() = runTest {
        val transport = FakeRadioTransport(
            TransportIdentity("fake:queue-only"),
            autoHandshake = false,
            autoQueueStatus = true,
        )
        transport.connect()

        transport.send(toRadio(ToRadio.Builder().also { it.want_config_id = STAGE1_NONCE }.build()))
        transport.send(
            toRadio(
                ToRadio.Builder().also {
                    it.packet = MeshPacket.Builder().also { p -> p.id = 77 }.build()
                }.build(),
            ),
        )

        val first = transport.frames().first()
        val status = WireCodec.decodeFromRadio(first.bytes.toByteArray().copyOfRange(4, first.bytes.size)).queueStatus
        assertEquals(77, status?.mesh_packet_id, "the packet gets a queue status")
        assertNull(withTimeoutOrNull(100) { transport.frames().first() }, "want_config gets no handshake reply")
    }

    private fun toRadio(message: ToRadio): Frame = Frame(WireCodec.encodeToRadio(message).toByteString())

    private companion object {
        const val STAGE1_NONCE = 69420
    }
}
