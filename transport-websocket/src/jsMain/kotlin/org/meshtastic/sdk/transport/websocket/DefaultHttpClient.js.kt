/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

// The browser's WebSocket owns TLS trust and mixed-content rules; an https page cannot open ws:// to another host.
// The JS engine refuses a maxFrameSize, so an oversized message is buffered in full before the transport's 512-byte
// check rejects it.
internal actual fun createDefaultHttpClient(): HttpClient = HttpClient(Js) {
    install(WebSockets)
}
