/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Channel
import org.meshtastic.proto.NodeInfo
import kotlin.time.Clock

/**
 * [DeviceStorage] held entirely in RAM.
 *
 * Nothing survives the process: a host that wants a NodeDB back after restart wires
 * `:storage-sqldelight` or its own backend. What this one gives a production host is a
 * [StorageProvider] with no database driver, no files and no platform `Context`, for
 * one-shot tools, gateways that rebuild state from the handshake anyway, and the time
 * before a persistent backend is chosen. It honors the [recordOwnNode] factory-reset
 * contract: a changed NodeNum clears everything before the new tuple is recorded.
 *
 * Calls are serialized by the engine actor, as the [DeviceStorage] contract states, so the
 * maps need no locking.
 *
 * @since 0.3.0
 */
public class InMemoryStorage : DeviceStorage {
    private val nodes = mutableMapOf<NodeId, NodeInfo>()
    private var config: ConfigBundle? = null
    private val channelsList = mutableListOf<Channel>()
    private var recordedNodeNum: NodeId? = null
    private var recordedFirmwareVersion: String? = null
    private var sessionPasskey: SessionPasskey? = null
    private val heartbeats = mutableMapOf<NodeId, Long>()

    override suspend fun loadNodes(): Map<NodeId, NodeInfo> = nodes.toMap()

    override suspend fun saveNode(node: NodeInfo) {
        nodes[NodeId(node.num)] = node
    }

    override suspend fun removeNode(nodeId: NodeId) {
        nodes.remove(nodeId)
    }

    override suspend fun loadConfig(): ConfigBundle? = config

    override suspend fun saveConfig(config: ConfigBundle) {
        this.config = config
    }

    override suspend fun loadChannels(): List<Channel> = channelsList.toList()

    override suspend fun saveChannels(channels: List<Channel>) {
        channelsList.clear()
        channelsList.addAll(channels)
    }

    override suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String) {
        val prevNodeNum = recordedNodeNum
        if (prevNodeNum != null && prevNodeNum != nodeNum) {
            clear()
        }
        recordedNodeNum = nodeNum
        recordedFirmwareVersion = firmwareVersion
    }

    override suspend fun clear() {
        nodes.clear()
        config = null
        channelsList.clear()
        recordedNodeNum = null
        recordedFirmwareVersion = null
        sessionPasskey = null
        heartbeats.clear()
    }

    override suspend fun saveSessionPasskey(passkey: SessionPasskey) {
        sessionPasskey = passkey
    }

    override suspend fun loadSessionPasskey(): SessionPasskey? {
        val current = sessionPasskey ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        return if (current.expiresAtEpochMs <= now) {
            sessionPasskey = null
            null
        } else {
            current
        }
    }

    override suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long) {
        heartbeats[nodeId] = epochMillis
    }

    override suspend fun loadHeartbeats(): Map<NodeId, Long> = heartbeats.toMap()

    override fun close() {
        // Nothing to release.
    }
}

/**
 * [StorageProvider] that hands out a fresh [InMemoryStorage] per activation.
 *
 * ```kotlin
 * RadioClient.Builder()
 *     .transport(transport)
 *     .storage(InMemoryStorageProvider())
 *     .build()
 * ```
 *
 * @since 0.3.0
 */
public class InMemoryStorageProvider : StorageProvider {
    override suspend fun activate(identity: TransportIdentity): DeviceStorage = InMemoryStorage()
}
