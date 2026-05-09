/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteDiscoveryResultTest {
    @Test fun fromProtoAssemblesFullRoutes() {
        val source = NodeId(0x111)
        val destination = NodeId(0x444)

        val result = RouteDiscoveryResult.fromProto(
            source = source,
            destination = destination,
            intermediateRoute = listOf(0x222, 0x333),
            intermediateRouteBack = listOf(0x333, 0x222),
            snrTowards = listOf(40f, 32f),
            snrBack = listOf(36f, 28f),
        )

        assertEquals(listOf(source, NodeId(0x222), NodeId(0x333), destination), result.route)
        assertEquals(listOf(destination, NodeId(0x333), NodeId(0x222), source), result.routeBack)
        assertEquals(listOf(40f, 32f), result.snrTowards)
        assertEquals(listOf(36f, 28f), result.snrBack)
    }

    @Test fun hopsAwayCountsIntermediateNodes() {
        val routed = RouteDiscoveryResult(
            route = listOf(NodeId(1), NodeId(2), NodeId(3), NodeId(4)),
            routeBack = listOf(NodeId(4), NodeId(1)),
        )
        val direct = RouteDiscoveryResult.fromProto(
            source = NodeId(10),
            destination = NodeId(20),
            intermediateRoute = emptyList(),
            intermediateRouteBack = emptyList(),
        )

        assertEquals(2, routed.hopsAway)
        assertEquals(0, direct.hopsAway)
        assertEquals(listOf(NodeId(10), NodeId(20)), direct.route)
        assertEquals(listOf(NodeId(20), NodeId(10)), direct.routeBack)
    }

    @Test fun formatRouteProducesReadableOutput() {
        val result = RouteDiscoveryResult(
            route = listOf(NodeId(1), NodeId(2), NodeId(3)),
            routeBack = listOf(NodeId(3), NodeId(1)),
            snrTowards = listOf(10.5f, 8.25f),
            snrBack = listOf(7.75f),
        )

        val formatted = result.formatRoute { node -> "Node-${node.raw}" }

        assertEquals(
            """
            Route (3 nodes):
              Node-1 (SNR: 10.5)
              Node-2 (SNR: 8.25)
              Node-3
            Route back (2 nodes):
              Node-3 (SNR: 7.75)
              Node-1
            """.trimIndent(),
            formatted.trimEnd(),
        )
    }
}
