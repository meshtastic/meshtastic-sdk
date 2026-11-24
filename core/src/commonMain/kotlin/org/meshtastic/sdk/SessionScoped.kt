/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * A side-stream [value] with the [RadioSessionEvent.sessionId] of the session it arrived in. Ids match
 * [RadioClient.sessionEvents] and start at 1 with the first handshake, whether or not session receive is
 * enabled; `0` means no session has begun. An item between two sessions carries the id of the last one.
 *
 * @since 0.3.0
 */
public data class SessionScoped<out T>(public val sessionId: Long, public val value: T)
