/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import org.meshtastic.proto.RemoteShell
import kotlin.coroutines.cancellation.CancellationException

/**
 * Remote shell frames on `REMOTE_SHELL_APP`. The SDK carries frames; session state, sequencing
 * and retransmission belong to the caller.
 *
 * Acquired via [RadioClient.remoteShell].
 *
 * @since 0.3.0
 */
public interface RemoteShellApi {

    /**
     * Send [frame] to [node], PKI-encrypted with the key stored for [node], on channel `0` with no
     * `want_ack`.
     *
     * @throws MeshtasticException.Protocol if no 32-byte public key is stored for [node]
     * @throws MeshtasticException.NotConnected if not currently connected
     */
    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun send(node: NodeId, frame: RemoteShell): MessageHandle

    /** Inbound frames from [node], decoded. Cold; collect inside a `launch { … }`. */
    public fun frames(node: NodeId): Flow<RemoteShell>

    /** A random non-zero `session_id` for a new session. */
    public fun newSessionId(): Int
}
