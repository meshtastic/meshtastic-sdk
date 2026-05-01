/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.meshtastic.proto.MeshPacket

/**
 * Tracks the lifecycle of a packet enqueued via [RadioClient.send].
 *
 * The [state] [StateFlow] transitions through [SendState] values as the packet progresses from
 * host queue to radio transmission to on-air delivery. Callers can observe [state] for reactive
 * updates or suspend on [await] for a one-shot result.
 *
 * **Thread safety:** [state] is a [StateFlow] — safe to observe from any coroutine. [cancel] is
 * safe to call from any thread or coroutine.
 *
 * @property id the unique identifier for this send (monotonically increasing within a session)
 * @since 0.1.0
 */
public class MessageHandle internal constructor(
    public val id: MessageId,
    internal val _state: MutableStateFlow<SendState>,
    private val cancelFn: (MessageId) -> Unit,
    internal val packet: MeshPacket? = null,
    internal val resendFn: ((MeshPacket) -> MessageHandle)? = null,
) {
    /**
     * The current delivery state.
     *
     * Transitions: [SendState.Queued] → [SendState.Sent] → [SendState.Acked] /
     * [SendState.Delivered] / [SendState.Failed]. Never goes backward.
     *
     * **Lifetime / terminal-on-disconnect:** the underlying [kotlinx.coroutines.flow.MutableStateFlow]
     * is owned by the engine session that created this handle. When that session ends — either via
     * [RadioClient.disconnect], a transport drop, or supervisor cancellation — the engine
     * transitions any non-terminal state to [SendState.Failed] (typically with
     * [SendFailure.Disconnected] for in-flight sends, or [SendFailure.HandshakeFailed] for sends
     * queued before the handshake completed). After that final transition the engine no longer
     * touches this flow, so the value observed here is the *terminal* state for this handle's
     * lifetime — a subsequent [RadioClient.connect] starts a fresh session with new handles.
     */
    public val state: StateFlow<SendState> = _state.asStateFlow()

    /**
     * Suspend until this send reaches a terminal state ([Acked][SendState.Acked],
     * [Delivered][SendState.Delivered], or [Failed][SendState.Failed]).
     *
     * Returns immediately if already in a terminal state.
     *
     * **Disconnect behaviour:** if the engine disconnects (transport drop, [RadioClient.disconnect],
     * or host scope cancel) before a terminal state, [state] transitions to
     * `Failed(Disconnected)` and [await] returns [SendOutcome.Failure].
     *
     * **Cancellation:** if the *caller's* coroutine is cancelled while suspended in [await], the
     * function rethrows [kotlinx.coroutines.CancellationException]. The underlying handle is
     * **unaffected** — the engine continues to track the send and updates [state] for any other
     * observer. Use [cancel] to actively withdraw the send.
     */
    public suspend fun await(): SendOutcome {
        val terminal = _state.first { s ->
            s is SendState.Acked || s is SendState.Delivered || s is SendState.Failed
        }
        return when (terminal) {
            is SendState.Acked, is SendState.Delivered -> SendOutcome.Success

            is SendState.Failed -> SendOutcome.Failure(terminal.reason)

            else -> throw MeshtasticException.tag(
                MeshtasticException.Protocol("MessageHandle.await reached non-terminal state: $terminal"),
                operation = "MessageHandle.await",
            )
        }
    }

    /**
     * Best-effort cancellation. Idempotent and non-suspending.
     *
     * - If the packet is still [Queued][SendState.Queued]: removed from the host outbound queue;
     *   [state] transitions to `Failed(Cancelled)`.
     * - If the packet is [Sent][SendState.Sent] or later: no effect on the radio (the device and
     *   mesh continue processing the packet); [state] is not modified.
     *
     * Callers suspended in [await] on this handle will wake when [state] settles.
     */
    public fun cancel() {
        cancelFn(id)
    }
}
