/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.Channel
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.NodeId

/**
 * Immutable snapshot of the engine's current state.
 *
 * The engine maintains one `MeshState` instance and replaces it (never mutates it) when state
 * changes. This ensures thread-safe observation of state through published flows.
 */
internal data class MeshState(
    // Session state
    val myInfo: MyNodeInfo? = null,
    val configBundle: ConfigBundle? = null,
    val channels: List<Channel> = emptyList(),

    // Node database
    val nodes: Map<NodeId, NodeInfo> = emptyMap(),

    // Version tracking for state change detection
    val version: Int = 0,
) {
    /**
     * Create a new state with updated nodes.
     */
    fun withNodes(newNodes: Map<NodeId, NodeInfo>): MeshState = copy(nodes = newNodes, version = version + 1)

    /**
     * Create a new state with updated myInfo.
     */
    fun withMyInfo(info: MyNodeInfo): MeshState = copy(myInfo = info, version = version + 1)

    /**
     * Create a new state with updated config.
     */
    fun withConfig(bundle: ConfigBundle): MeshState = copy(configBundle = bundle, version = version + 1)

    /**
     * Create a new state with updated channels.
     */
    fun withChannels(newChannels: List<Channel>): MeshState = copy(channels = newChannels, version = version + 1)

    /**
     * Clear all state (factory reset / node mismatch detected).
     */
    fun reset(): MeshState = MeshState(version = version + 1)
}
