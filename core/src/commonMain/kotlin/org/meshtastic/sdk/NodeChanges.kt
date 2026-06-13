/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.scan
import org.meshtastic.proto.NodeInfo

/**
 * Fold a [NodeChange] delta stream into a live node map.
 *
 * Applies the canonical accumulator every consumer otherwise hand-writes: a
 * [NodeChange.Snapshot] replaces the map wholesale (emitted on first subscription and after
 * reconnect), [NodeChange.Added]/[NodeChange.Updated] upsert, [NodeChange.Removed] deletes,
 * and the presence deltas ([NodeChange.WentOffline]/[NodeChange.CameOnline]) leave the map
 * untouched — presence is signalled, not stored.
 *
 * The first emission is an empty map (before the Snapshot lands), which makes the result
 * directly usable with `stateIn`:
 *
 * ```kotlin
 * val nodes: StateFlow<Map<NodeId, NodeInfo>> = client.nodeMap()
 *     .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
 * ```
 *
 * @since 0.2.0
 */
public fun Flow<NodeChange>.asNodeMap(): Flow<Map<NodeId, NodeInfo>> = scan(emptyMap()) { acc, change ->
    when (change) {
        is NodeChange.Snapshot -> change.nodes
        is NodeChange.Added -> acc + (NodeId(change.node.num) to change.node)
        is NodeChange.Updated -> acc + (NodeId(change.node.num) to change.node)
        is NodeChange.Removed -> acc - change.nodeId
        is NodeChange.WentOffline, is NodeChange.CameOnline -> acc
    }
}

/**
 * The live node map for this client: [RadioClient.nodes] folded via [asNodeMap].
 *
 * @since 0.2.0
 */
public fun RadioClient.nodeMap(): Flow<Map<NodeId, NodeInfo>> = nodes.asNodeMap()
