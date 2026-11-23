/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RemoteShell
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageHandle
import org.meshtastic.sdk.MqttProxyApi
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RemoteShellApi
import org.meshtastic.sdk.asRemoteShell
import kotlin.random.Random

private const val PKI_KEY_SIZE = 32

internal class RemoteShellApiImpl(private val client: RadioClient) : RemoteShellApi {

    override suspend fun send(node: NodeId, frame: RemoteShell): MessageHandle {
        val key = client.nodeSnapshot()[node]?.user?.public_key?.takeIf { it.size == PKI_KEY_SIZE }
            ?: throw MeshtasticException.Protocol("no public key stored for node ${node.raw.toUInt()}")
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = node.raw
            wb.channel = 0
            wb.want_ack = false
            wb.hop_limit = client.defaultHopLimit()
            wb.pki_encrypted = true
            wb.public_key = key
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.REMOTE_SHELL_APP
                wb.payload = RemoteShell.ADAPTER.encode(frame).toByteString()
            }.build()
        }.build()
        // NimBLE queues three inbound writes and drops the next (NimbleBluetooth.cpp onWrite), so a typed burst
        // waits for each frame's QueueStatus.
        return client.sendPaced(packet).message
    }

    override fun frames(node: NodeId): Flow<RemoteShell> = client.packets
        .filter { it.from == node.raw }
        .mapNotNull { it.asRemoteShell() }

    override fun newSessionId(): Int {
        while (true) {
            val id = Random.nextInt()
            if (id != 0) return id
        }
    }
}

internal class MqttProxyApiImpl(private val client: RadioClient) : MqttProxyApi {

    override val messages: Flow<MqttClientProxyMessage> = client.events
        .filterIsInstance<MeshEvent.MqttProxyMessage>()
        .map { it.message }

    override suspend fun deliver(message: MqttClientProxyMessage): Boolean =
        client.trySendRaw(ToRadio.Builder().also { wb -> wb.mqttClientProxyMessage = message }.build())
}
