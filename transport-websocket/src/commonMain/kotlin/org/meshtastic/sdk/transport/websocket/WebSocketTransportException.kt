/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import org.meshtastic.sdk.MeshtasticException

/**
 * A [MeshtasticException.Transport] from [WebSocketTransport] that carries the close code, close
 * reason and upgrade status as fields, so a caller never parses [message].
 *
 * [WebSocketTransport] publishes it as the `cause` of the `TransportState.Error` that ends a
 * session, and throws it from [WebSocketTransport.connect] when the node can't be reached or
 * refuses the upgrade. Other failures (a connect interrupted by `disconnect()`, a failed send)
 * stay a plain [MeshtasticException.Transport].
 *
 * @property closeCode the close code that ended the session: the node's close frame (4001 another
 *   client took the slot, 1009 message too big, 1013 out of memory, 1000 normal or idle), or 1009
 *   when the transport closed the session itself over a message larger than 512 bytes. 1006, or
 *   `null`, when the connection dropped without a close frame; `null` on a failed connect.
 * @property closeReason the reason text of the node's close frame; `null` when it was blank or no
 *   close frame arrived.
 * @property httpStatus the HTTP status the node answered the upgrade request with, when it refused
 *   it (404 when its firmware has no WebSocket phone API at the path); `null` otherwise.
 * @property slotContested `true` when the transport ended the fifth session in a row that dropped
 *   without a close frame within 30 seconds of opening, which means another client keeps taking the
 *   node's only WebSocket slot. Such an end is reported with `recoverable = false`.
 * @since 0.3.0
 */
public class WebSocketTransportException internal constructor(
    message: String,
    public val closeCode: Int? = null,
    public val closeReason: String? = null,
    public val httpStatus: Int? = null,
    public val slotContested: Boolean = false,
    cause: Throwable? = null,
) : MeshtasticException.Transport(message, cause)
