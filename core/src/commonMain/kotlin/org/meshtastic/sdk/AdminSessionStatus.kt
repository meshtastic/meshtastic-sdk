/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.time.Instant

/**
 * State of the admin session with one node, as seen by [RadioClient.adminSessions].
 *
 * A node issues an 8-byte session passkey in every admin response and accepts it for 300 s after
 * it generated the key. It generates a new key on the first response sent once the current key is
 * older than 150 s, so a key in a response was at most 150 s old when the node sent it and had at
 * least 150 s of validity left at that moment. The client cannot see when the key was generated
 * and cannot measure how long a response took to arrive. A key received at time `T` is therefore
 * expected to be accepted until `T + 150 s` ([Active]), may be accepted until `T + 300 s`
 * ([Stale]), and is certainly rejected after that ([NoSession]). [Active] is an expectation, not
 * a guarantee: mesh delivery delay shortens it, and a node that rejects a key in any state is
 * handled by the single re-authentication retry. Source: `firmware/src/modules/AdminModule.cpp`
 * `setPassKey` and `checkPassKey`.
 *
 * The passkey itself is never exposed.
 *
 * @since 0.3.0
 */
public sealed interface AdminSessionStatus {

    /**
     * No usable passkey is on file: none was issued this connection, the last one expired, or the
     * node rejected it with `ADMIN_BAD_SESSION_KEY`.
     *
     * @since 0.3.0
     */
    public data object NoSession : AdminSessionStatus

    /**
     * A passkey is on file and is expected to be accepted until [staleAt]. This is not a
     * guarantee: the node had at least 150 s of validity left when it sent the key, minus the
     * delivery delay the SDK cannot measure for a remote node, so a request inside this window can
     * still be rejected. The existing single retry on `ADMIN_BAD_SESSION_KEY` handles that.
     *
     * @property refreshedAt when the engine received the response carrying the passkey
     * @property staleAt `refreshedAt` plus 150 s, the minimum validity the node had left when it sent the key
     * @since 0.3.0
     */
    public data class Active(val refreshedAt: Instant, val staleAt: Instant) : AdminSessionStatus

    /**
     * A passkey is on file that the node may already have rotated. The engine still sends it, and
     * an admin call that the node rejects re-authenticates once. Any admin response from the node
     * makes the session [Active] again.
     *
     * @property refreshedAt when the engine last observed a passkey from the node
     * @property expiresAt `refreshedAt` plus 300 s; the node certainly rejects the key after this
     * @since 0.3.0
     */
    public data class Stale(val refreshedAt: Instant, val expiresAt: Instant) : AdminSessionStatus
}
