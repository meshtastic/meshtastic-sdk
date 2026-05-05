/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.delay

/**
 * Re-enqueue the same packet that produced this [MessageHandle]. The engine assigns
 * a fresh `MessageId` and returns a new handle, leaving the original handle's terminal state
 * unchanged.
 *
 * Throws [MeshtasticException.Protocol] if the handle was constructed without a stashed
 * packet (e.g. by a test double); built-in `RadioClient.send()` always populates one.
 */
public suspend fun MessageHandle.retry(): MessageHandle {
    val pkt = packet
    val resend = resendFn
    if (pkt == null || resend == null) {
        throw MeshtasticException.Protocol(
            "MessageHandle.retry() requires a handle produced by RadioClient.send()",
        )
    }
    return resend(pkt)
}

/**
 * Awaits the outcome of this [MessageHandle], retrying according to [policy] if the send fails
 * with a retryable failure.
 *
 * Non-retryable failures ([SendFailure.Disconnected], [SendFailure.Cancelled],
 * [SendFailure.HandshakeFailed]) abort immediately regardless of remaining attempts.
 *
 * Requires that [MessageHandle.packet] and [MessageHandle.resendFn] are non-null (i.e., the handle
 * was produced by [RadioClient.send] or [RadioClient.sendText], which always populate these fields).
 *
 * @param policy the retry strategy to apply
 * @return the final [SendOutcome] (success after any attempt, or the last failure)
 */
public suspend fun MessageHandle.retryWith(policy: RetryPolicy): SendOutcome {
    if (policy is RetryPolicy.None) return await()

    var currentHandle = this
    var attempt = 0

    while (true) {
        val outcome = currentHandle.await()
        if (outcome is SendOutcome.Success) return outcome

        val failure = (outcome as SendOutcome.Failure).reason
        if (!failure.isRetryable()) return outcome

        val delayDuration = policy.delayForAttempt(attempt) ?: return outcome
        val pkt = currentHandle.packet ?: return outcome
        val resend = currentHandle.resendFn ?: return outcome

        delay(delayDuration)
        currentHandle = resend(pkt)
        attempt++
    }
}

/**
 * Whether this failure type is safe to retry.
 *
 * Terminal failures (disconnected, cancelled, handshake) should never be retried because the
 * underlying session is no longer valid.
 */
private fun SendFailure.isRetryable(): Boolean = when (this) {
    SendFailure.Disconnected,
    SendFailure.HandshakeFailed,
    SendFailure.Cancelled,
    SendFailure.IdCollision,
    -> false

    SendFailure.NoRoute,
    SendFailure.MaxRetransmit,
    SendFailure.Timeout,
    SendFailure.DutyCycleLimit,
    SendFailure.AckTimeout,
    is SendFailure.Other,
    is SendFailure.Unknown,
    -> true
}
