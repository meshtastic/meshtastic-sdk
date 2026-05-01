/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.first
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient

/**
 * Suspend until the device finishes streaming its NodeDB during handshake, then return the
 * current node-snapshot map.
 *
 * This waits for [RadioClient.connection] to reach [ConnectionState.Connected] (which the
 * engine only signals after the handshake completes) and then snapshots the engine's
 * NodeDB.
 *
 * Cancellation is cooperative: cancelling the caller's coroutine simply unsubscribes from
 * the flow; the engine is unaffected.
 */
public suspend fun RadioClient.awaitInitialNodeDb(): Map<NodeId, NodeInfo> {
    connection.first { it is ConnectionState.Connected }
    return nodeSnapshot()
}
