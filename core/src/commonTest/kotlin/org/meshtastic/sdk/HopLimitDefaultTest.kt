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
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `null` hop limit on the convenience sends resolves on the host. The firmware only
 * substitutes its default for a `0` on the wire when `want_ack` is set, so sending `0` with
 * `want_ack` off would produce a packet nothing relays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HopLimitDefaultTest {

    @Test
    fun typedSendUsesDeviceHopLimitWhenCallerPassesNull() = runTest {
        val (transport, client) = connectedClient(loraHopLimit = 5)
        try {
            client.send(PortNum.TEXT_MESSAGE_APP, "hi".encodeToByteArray(), wantAck = false)
            runCurrent()
            assertEquals(5, transport.outboundPackets().last().hop_limit)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun dslSendUsesDeviceHopLimitWhenNotSet() = runTest {
        val (transport, client) = connectedClient(loraHopLimit = 5)
        try {
            client.send { text("hi") }
            runCurrent()
            assertEquals(5, transport.outboundPackets().last().hop_limit)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun explicitHopLimitWinsOverDeviceDefault() = runTest {
        val (transport, client) = connectedClient(loraHopLimit = 5)
        try {
            client.send(PortNum.TEXT_MESSAGE_APP, "hi".encodeToByteArray(), hopLimit = 1)
            client.send {
                text("hi")
                hopLimit(2)
            }
            runCurrent()
            val sent = transport.outboundPackets().takeLast(2).map { it.hop_limit }
            assertEquals(listOf(1, 2), sent)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun unsetDeviceHopLimitFallsBackToFirmwareDefault() = runTest {
        val (transport, client) = connectedClient(loraHopLimit = 0)
        try {
            client.send(PortNum.TEXT_MESSAGE_APP, "hi".encodeToByteArray())
            runCurrent()
            assertEquals(3, transport.outboundPackets().last().hop_limit)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun missingConfigBundleFallsBackToFirmwareDefault() = runTest {
        val (transport, client) = connectedClient(loraHopLimit = null)
        try {
            client.send { text("hi") }
            runCurrent()
            assertEquals(3, transport.outboundPackets().last().hop_limit)
        } finally {
            client.disconnect()
        }
    }

    private suspend fun TestScope.connectedClient(loraHopLimit: Int?): Pair<FakeRadioTransport, RadioClient> {
        val frames = if (loraHopLimit == null) {
            emptyList()
        } else {
            listOf(
                FromRadio.Builder().also { wb ->
                    wb.metadata = DeviceMetadata.Builder().also { wb -> wb.firmware_version = "2.7.0" }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.lora = Config.LoRaConfig.Builder().also { wb -> wb.hop_limit = loraHopLimit }.build()
                    }.build()
                }.build().toFrame(),
            )
        }
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:hop-limit"),
            frames = frames,
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)
        return transport to client
    }
}
