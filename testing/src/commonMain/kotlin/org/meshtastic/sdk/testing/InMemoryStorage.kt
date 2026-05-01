/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.testing

import org.meshtastic.proto.Channel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SessionPasskey
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportIdentity
import kotlin.time.Clock

/**
 * In-memory storage for testing.
 *
 * All data is held in RAM; persistence is not tested. Explicitly NOT a default in the SDK —
 * only for test scenarios.
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
            // Factory reset or radio swap detected
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
        // No-op for in-memory storage
    }
}

/**
 * Provider for in-memory storage.
 *
 * Always returns a fresh [InMemoryStorage] instance per activation.
 */
public class InMemoryStorageProvider : StorageProvider {
    override suspend fun activate(identity: TransportIdentity): DeviceStorage = InMemoryStorage()
}
