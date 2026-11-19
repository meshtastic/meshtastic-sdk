/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

internal const val DEFAULT_WS_PATH: String = "/api/v1/ws"

/** A validated `ws`/`wss` endpoint. [host] is lowercase and unbracketed. */
internal class WebSocketEndpoint(val scheme: String, val host: String, val port: Int, val path: String) {
    private val authority: String = (if (host.contains(':')) "[$host]" else host) + ":$port"

    /** The URL handed to the Ktor client; always carries an explicit port. */
    val url: String = "$scheme://$authority$path"

    /** Stable key for the same node URL; the transport identity is `ws:` plus this. */
    val identityKey: String = url
}

/**
 * Parses a `ws://` or `wss://` URL. A blank or `/` path becomes [DEFAULT_WS_PATH]; userinfo, queries,
 * fragments, other schemes and ports outside 1..65535 are refused with [IllegalArgumentException].
 */
internal fun parseWebSocketEndpoint(raw: String): WebSocketEndpoint {
    val input = raw.trim()
    val schemeEnd = input.indexOf("://")
    require(schemeEnd > 0) { "WebSocket URL must start with ws:// or wss://: '$raw'" }
    val scheme = input.substring(0, schemeEnd).lowercase()
    require(scheme == "ws" || scheme == "wss") { "WebSocket URL scheme must be ws or wss, not '$scheme'" }

    val rest = input.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    val tail = rest.substring(authorityEnd)
    require('?' !in tail && '#' !in tail) { "WebSocket URL must not carry a query or fragment: '$raw'" }
    require('@' !in authority) { "WebSocket URL must not carry user info: '$raw'" }

    val host: String
    val portText: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        require(close > 1) { "Invalid IPv6 host in WebSocket URL: '$raw'" }
        host = authority.substring(1, close)
        val after = authority.substring(close + 1)
        require(after.isEmpty() || after.startsWith(":")) { "Invalid authority in WebSocket URL: '$raw'" }
        portText = if (after.isEmpty()) null else after.substring(1)
    } else {
        val colon = authority.lastIndexOf(':')
        host = if (colon < 0) authority else authority.substring(0, colon)
        portText = if (colon < 0) null else authority.substring(colon + 1)
    }
    require(host.isNotBlank() && host.none { it.isWhitespace() }) { "WebSocket URL has no valid host: '$raw'" }

    val port = if (portText == null) {
        if (scheme == "wss") 443 else 80
    } else {
        val parsed = portText.takeIf { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }?.toIntOrNull()
        require(parsed != null && parsed in 1..65535) { "WebSocket URL port must be 1..65535: '$raw'" }
        parsed
    }

    val path = if (tail.isEmpty() || tail == "/") DEFAULT_WS_PATH else tail
    return WebSocketEndpoint(scheme, host.lowercase(), port, path)
}
