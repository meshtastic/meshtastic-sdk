/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Routing

/**
 * Lifecycle state of a packet sent via [RadioClient.send].
 *
 * A [MessageHandle] has a [state] that transitions through these states based on device feedback.
 * Expected radio failures (NAK, timeout) are **not** exceptions — they are represented here.
 *
 * @since 0.1.0
 */
public sealed interface SendState {
    /**
     * The packet is queued by the SDK, awaiting transmission to the device.
     */
    public data object Queued : SendState

    /**
     * The device has accepted the packet and started transmission.
     *
     * (From `QueueStatus`.)
     */
    public data object Sent : SendState

    /**
     * The device received an acknowledgment from the target (unicast only).
     *
     * (From device-emitted `Routing` ACK.)
     */
    public data object Acked : SendState

    /**
     * A broadcast packet was rebroadcast by another node.
     *
     * (From device-emitted `Routing` broadcast ack.)
     */
    public data object Delivered : SendState

    /**
     * Packet delivery failed.
     *
     * @param reason why the packet could not be delivered
     */
    public data class Failed(val reason: SendFailure) : SendState
}

/**
 * Reason a packet could not be delivered.
 *
 * Represents expected radio failures (no route, timeout) and transport issues. Not thrown as an
 * exception — observed via [MessageHandle.state].
 *
 * @since 0.1.0
 */
public sealed interface SendFailure {
    /**
     * No route to the destination (from `Routing.Error.NO_ROUTE`).
     */
    public data object NoRoute : SendFailure

    /**
     * Device exhausted its retransmit budget (from `Routing.Error.MAX_RETRANSMIT`).
     */
    public data object MaxRetransmit : SendFailure

    /**
     * Admin operation timed out waiting for a response.
     */
    public data object Timeout : SendFailure

    /**
     * Device's duty-cycle limit prevented transmission (from `Routing.Error.DUTY_CYCLE_LIMIT`).
     */
    public data object DutyCycleLimit : SendFailure

    /**
     * Transport disconnected before the packet could be sent.
     */
    public data object Disconnected : SendFailure

    /**
     * The handshake failed (timeout or terminal error) before the packet could be dispatched.
     *
     * Sends queued before the session reached `Ready` are failed with this reason rather than
     * [Disconnected] so callers can distinguish "never made it through the handshake" from
     * "transport went away mid-session".
     */
    public data object HandshakeFailed : SendFailure

    /**
     * [MessageHandle.cancel] was called before the packet left the host queue.
     */
    public data object Cancelled : SendFailure

    /**
     * Caller submitted a packet whose `id` matches an in-flight send that has not yet reached a
     * terminal state. The new send is rejected; the existing in-flight handle is preserved.
     */
    public data object IdCollision : SendFailure

    /**
     * Per-send ACK timeout: the device did not produce a `Routing` ACK for a unicast `want_ack`
     * packet within the configured [RadioClient.Builder.sendTimeout] window.
     *
     * Broadcast packets (`to == 0xFFFFFFFF`) never produce this failure; they have no ACK
     * semantics in the Meshtastic protocol.
     */
    public data object AckTimeout : SendFailure

    /**
     * Some other device-reported routing error.
     *
     * @param routingError the wire [Routing.Error] enum value
     */
    public data class Other(val routingError: Routing.Error) : SendFailure

    /**
     * Unknown failure (should not occur in normal operation).
     *
     * @param message diagnostic text
     */
    public data class Unknown(val message: String) : SendFailure
}

/**
 * Outcome of awaiting a [MessageHandle].
 *
 * Wraps the final [SendState] with a convenience to distinguish terminal states.
 *
 * @since 0.1.0
 */
public sealed interface SendOutcome {
    /**
     * Packet was delivered or acknowledged.
     */
    public data object Success : SendOutcome

    /**
     * Packet failed to deliver.
     *
     * @param reason the failure cause
     */
    public data class Failure(val reason: SendFailure) : SendOutcome
}

/**
 * Lifecycle of the SDK's connection to a device.
 *
 * Transitions from [Disconnected] → [Connecting] → [Configuring] → [Connected].
 * May also transition back through [Reconnecting] if the transport drops or hangs.
 *
 * **Device sleep is not represented:** devices do not announce sleep on the wire (the PhoneAPI
 * simply goes silent). The SDK cannot reliably distinguish "device sleeping for ls_secs" from
 * "transport hung". Sleep timing is observable via `Config.power.ls_secs` from the handshake; when
 * the device stops responding, the state machine transitions through [Reconnecting] just as for
 * any other disconnect.
 *
 * @since 0.1.0
 */
public sealed interface ConnectionState {
    /**
     * SDK is not connected and has no active connection attempt.
     */
    public data object Disconnected : ConnectionState

    /**
     * Attempting to establish a connection.
     *
     * @param attempt the attempt count (1-indexed)
     */
    public data class Connecting(val attempt: Int) : ConnectionState

    /**
     * Transport is up; completing handshake configuration.
     *
     * @param phase the current handshake phase (Stage1/Stage2/etc.)
     * @param progress estimated completion (0.0 to 1.0)
     */
    public data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState

    /**
     * Handshake complete; session is ready for use.
     */
    public data object Connected : ConnectionState

    /**
     * Connection dropped unexpectedly; attempting automatic reconnect.
     *
     * @param cause the underlying error that triggered reconnection
     * @param attempt the reconnect attempt count (1-indexed)
     */
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int) : ConnectionState
}

/** Whether the connection is fully established and ready for use. */
public val ConnectionState.isUsable: Boolean
    get() = this is ConnectionState.Connected

/** Whether a connection attempt is actively in progress. */
public val ConnectionState.isInProgress: Boolean
    get() =
        this is ConnectionState.Connecting ||
            this is ConnectionState.Configuring ||
            this is ConnectionState.Reconnecting

/** Human-readable status description. */
public val ConnectionState.statusMessage: String
    get() = when (this) {
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Connecting -> "Connecting (attempt $attempt)"
        is ConnectionState.Configuring -> "Configuring: ${phase.name} (${(progress * 100).toInt()}%)"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Reconnecting -> "Reconnecting (attempt $attempt)"
    }

/**
 * Handshake phase for progress reporting.
 *
 * @since 0.1.0
 */
public enum class ConfigPhase {
    /**
     * First handshake stage (metadata, device config, channels).
     */
    Stage1,

    /**
     * Settling between stages.
     */
    Settling,

    /**
     * Second handshake stage (node database).
     */
    Stage2,
}

/**
 * Result of an admin (configuration) RPC operation.
 *
 * Admin operations can fail for expected reasons (device unreachable, unauthorized, timeout)
 * without throwing an exception.
 *
 * @since 0.1.0
 */
public sealed interface AdminResult<out T> {
    /**
     * Operation succeeded.
     *
     * @param value the response payload
     */
    public data class Success<T>(val value: T) : AdminResult<T>

    /**
     * Session key expired or was never established.
     *
     * (From `AdminMessage.ADMIN_BAD_SESSION_KEY`.)
     */
    public data object SessionKeyExpired : AdminResult<Nothing>

    /**
     * Client is not authorized to perform this operation.
     *
     * (From `AdminMessage.NOT_AUTHORIZED` or `AdminMessage.ADMIN_PUBLIC_KEY_UNAUTHORIZED`.)
     */
    public data object Unauthorized : AdminResult<Nothing>

    /**
     * Operation timed out waiting for a device response.
     */
    public data object Timeout : AdminResult<Nothing>

    /**
     * Destination node is unreachable.
     */
    public data object NodeUnreachable : AdminResult<Nothing>

    /**
     * Device reported a routing error.
     *
     * @param routingError the wire [Routing.Error] enum value
     */
    public data class Failed(val routingError: Routing.Error) : AdminResult<Nothing>
}

// ── AdminResult extensions ──────────────────────────────────────────────────

/** Returns the [Success] value, or `null` if the result is not a success. */
public fun <T> AdminResult<T>.getOrNull(): T? = (this as? AdminResult.Success<T>)?.value

/** Returns the [Success] value, or [default] if the result is not a success. */
public fun <T> AdminResult<T>.getOrElse(default: T): T = getOrNull() ?: default

/** Returns the [Success] value, or the result of [block] if the result is not a success. */
public inline fun <T> AdminResult<T>.getOrElse(block: (AdminResult<T>) -> T): T =
    when (this) {
        is AdminResult.Success -> value
        else -> block(this)
    }

/** Returns `true` if this is [AdminResult.Success]. */
public val <T> AdminResult<T>.isSuccess: Boolean get() = this is AdminResult.Success

/** Transforms the [Success] value with [transform], propagating failures unchanged. */
public inline fun <T, R> AdminResult<T>.map(transform: (T) -> R): AdminResult<R> =
    when (this) {
        is AdminResult.Success -> AdminResult.Success(transform(value))
        is AdminResult.SessionKeyExpired -> this
        is AdminResult.Unauthorized -> this
        is AdminResult.Timeout -> this
        is AdminResult.NodeUnreachable -> this
        is AdminResult.Failed -> this
    }

/** Applies [onSuccess] or [onFailure] depending on the result. */
public inline fun <T, R> AdminResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AdminResult<T>) -> R,
): R = when (this) {
    is AdminResult.Success -> onSuccess(value)
    else -> onFailure(this)
}

/** Performs [action] if this is a [Success]. Returns the original result for chaining. */
public inline fun <T> AdminResult<T>.onSuccess(action: (T) -> Unit): AdminResult<T> {
    if (this is AdminResult.Success) action(value)
    return this
}

/** Performs [action] if this is not a [Success]. Returns the original result for chaining. */
public inline fun <T> AdminResult<T>.onFailure(action: (AdminResult<T>) -> Unit): AdminResult<T> {
    if (this !is AdminResult.Success) action(this)
    return this
}
