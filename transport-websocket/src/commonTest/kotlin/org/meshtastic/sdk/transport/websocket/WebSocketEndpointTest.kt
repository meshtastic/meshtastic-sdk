/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebSocketEndpointTest {

    private fun url(raw: String) = parseWebSocketEndpoint(raw).url

    @Test
    fun bareHostGetsDefaultPortAndPath() {
        assertEquals("ws://node.local:80/api/v1/ws", url("ws://node.local"))
        assertEquals("wss://node.local:443/api/v1/ws", url("wss://node.local"))
    }

    @Test
    fun trailingSlashBecomesDefaultPath() {
        assertEquals("ws://10.0.0.5:8080/api/v1/ws", url("ws://10.0.0.5:8080/"))
    }

    @Test
    fun explicitPathIsKept() {
        assertEquals("ws://node.local:80/custom/ws", url("ws://node.local/custom/ws"))
    }

    @Test
    fun hostAndSchemeAreLowercased() {
        assertEquals("wss://node.local:9443/api/v1/ws", url("WSS://Node.LOCAL:9443"))
    }

    @Test
    fun ipv6HostKeepsBrackets() {
        assertEquals("ws://[::1]:8080/api/v1/ws", url("ws://[::1]:8080"))
    }

    @Test
    fun portBoundsAreEnforced() {
        assertEquals("ws://h:1/api/v1/ws", url("ws://h:1"))
        assertEquals("ws://h:65535/api/v1/ws", url("ws://h:65535"))
        for (bad in listOf("ws://h:0", "ws://h:65536", "ws://h:-1", "ws://h:", "ws://h:80a")) {
            assertFailsWith<IllegalArgumentException>(bad) { parseWebSocketEndpoint(bad) }
        }
    }

    @Test
    fun userInfoIsRefused() {
        assertFailsWith<IllegalArgumentException> { parseWebSocketEndpoint("ws://user@node.local") }
        assertFailsWith<IllegalArgumentException> { parseWebSocketEndpoint("ws://user:pw@node.local") }
    }

    @Test
    fun otherSchemesAndMalformedInputAreRefused() {
        for (bad in listOf("http://node.local", "https://node.local", "node.local", "ws://", "ws:///api/v1/ws", "")) {
            assertFailsWith<IllegalArgumentException>(bad) { parseWebSocketEndpoint(bad) }
        }
    }

    @Test
    fun queryAndFragmentAreRefused() {
        assertFailsWith<IllegalArgumentException> { parseWebSocketEndpoint("ws://h/api/v1/ws?x=1") }
        assertFailsWith<IllegalArgumentException> { parseWebSocketEndpoint("ws://h#frag") }
    }

    @Test
    fun identityIsStableAcrossEquivalentUrls() {
        val a = WebSocketTransport("ws://Node.local").identity
        val b = WebSocketTransport("ws://node.local:80/api/v1/ws").identity
        assertEquals(a, b)
        assertEquals("ws:ws://node.local:80/api/v1/ws", a.raw)
    }
}
