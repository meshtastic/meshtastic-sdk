/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import io.ktor.client.HttpClient

/** Receive limit on SDK-created clients: a small bound above firmware's 512 byte message limit. */
internal const val MAX_INBOUND_FRAME_BYTES: Long = 1024

/** Builds the SDK-owned client: the platform engine with the WebSockets plugin, no pings and a bounded frame size. */
internal expect fun createDefaultHttpClient(): HttpClient
