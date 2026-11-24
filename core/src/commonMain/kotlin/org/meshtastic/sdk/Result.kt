/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Routing
import kotlin.time.Instant

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
     * The SDK has handed the packet to the transport writer.
     *
     * Use [PacedMessageHandle.awaitQueueReceipt] to distinguish confirmed radio admission.
     */
    public data object Sent : SendState

    /**
     * The radio reported a positive routing result for a unicast. A paced send without requested
     * routing feedback completes here on confirmed radio admission. An ordinary [RadioClient.send]
     * broadcast with `want_ack = false` and no `want_response` completes here as soon as it is
     * handed to the transport, with no radio confirmation at all.
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
     * The device refused the packet because the client sent on this port too soon after the last
     * one (from `Routing.Error.RATE_LIMIT_EXCEEDED`). Firmware allows one text message every two
     * seconds. Nothing was transmitted; wait and send again.
     */
    public data object RateLimited : SendFailure

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

    /** The SDK already holds the maximum number of paced sends. */
    public data object QueueFull : SendFailure

    /**
     * Per-send ACK timeout: the device did not produce a `Routing` ACK for a unicast `want_ack`
     * packet within the configured [RadioClient.Builder.sendAckTimeout] window.
     *
     * Broadcast packets (`to == 0xFFFFFFFF`) never produce this failure; they have no ACK
     * semantics in the Meshtastic protocol.
     */
    public data object AckTimeout : SendFailure

    /**
     * The device rejected the packet at its transmit queue (`QueueStatus.res != 0`), most
     * commonly because the firmware's bounded in-flight queue (~16 packets) is full.
     *
     * Surfaced immediately instead of waiting out the ACK timer. Callers should back off and
     * retry; bulk senders should pace submissions against this signal.
     *
     * @param res the raw `QueueStatus.res` error code reported by the firmware
     * @since 0.2.0
     */
    public data class QueueRejected(val res: Int) : SendFailure

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
 * With auto-reconnect on, a drop or hang goes through [Reconnecting] and back to [Configuring].
 * An error that retrying cannot fix ends in [Failed], with auto-reconnect on or off.
 *
 * **Device sleep:** a sleeping device's link simply drops. The engine reports
 * [DeviceSleeping] instead of [Reconnecting] only when a fully handshaken session drops and the
 * device's last known configuration says firmware sleeps it (see [DeviceSleeping]). A reboot
 * announcement, a failed first connect and [RadioClient.disconnect] are reported as [Reconnecting] or [Disconnected].
 *
 * @since 0.1.0
 */
public sealed interface ConnectionState {
    /**
     * SDK is not connected and has no active connection attempt.
     */
    public data object Disconnected : ConnectionState

    /**
     * [RadioClient.disconnect] was called and the engine is tearing the session down: cancelling a
     * pending attempt or backoff, ending the frame reader and closing the transport. [Disconnected]
     * follows. An unplanned link loss never shows this state, and a [RadioClient.disconnect] while
     * already [Disconnected] publishes nothing.
     *
     * @since 0.3.0
     */
    public data object Disconnecting : ConnectionState

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
     * @param attempt the reconnect attempt count since the last stable link (1-indexed)
     * @param retryAt when the engine will make [attempt], from the client's clock; `null` while the
     *   attempt is in flight, including while the [RadioTransportProvider] is suspended.
     *   [RadioClient.retryNow] ends the wait early.
     */
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int, val retryAt: Instant? = null) :
        ConnectionState

    /**
     * The engine stopped trying: the transport reported a non-recoverable error, the firmware is
     * too old ([MeshtasticException.FirmwareTooOld]), or an [AutoReconnectConfig] cap ran out. The
     * client stays here until [RadioClient.connect] or [RadioClient.disconnect] is called.
     *
     * @param cause the error that ended the last attempt, as its own type (a transport module's
     *   typed `MeshtasticException.Transport` subclass included)
     * @since 0.3.0
     */
    public data class Failed(val cause: MeshtasticException) : ConnectionState

    /**
     * The device dropped the link because its power-saving configuration puts it to sleep.
     *
     * Reported only for a BLE drop out of a fully handshaken session, however the engine notices
     * it (a transport error, a failed write or read, or the liveness watchdog), when the last
     * known config shows firmware sleeping that link by design: a tracker role with
     * `is_power_saving`, or a sensor role with `is_power_saving` and environment measurement on
     * (deep sleep, the device restarts on wake), or a router role or `is_power_saving` on a
     * WiFi-capable build with no WiFi or Ethernet configured (ESP32 light sleep). With
     * auto-reconnect enabled the engine keeps trying to reach the device; with it disabled the
     * session ends and this is the terminal state until [RadioClient.connect] or
     * [RadioClient.disconnect] is called.
     *
     * Reconnect attempts made before `since` plus [expectedWakeSeconds] plus a grace of twice
     * [AutoReconnectConfig.maxBackoff] do not count against [AutoReconnectConfig.maxAttempts], and
     * neither does a handshake that times out or drops in that window, since a BLE link can survive
     * light sleep; the device counts as asleep until a handshake reaches [Connected]. After the window
     * the state becomes [Reconnecting].
     *
     * @param since when the link drop was observed
     * @param expectedWakeSeconds the longest the device sleeps before it surfaces again:
     *   `Config.position.position_broadcast_secs` for a tracker,
     *   `ModuleConfig.telemetry.environment_update_interval` for a sensor (firmware's one hour when
     *   either is 0), `Config.power.ls_secs` for light sleep, a router's included
     * @since 0.3.0
     */
    public data class DeviceSleeping(val since: Instant, val expectedWakeSeconds: Int) : ConnectionState
}

/** Whether the connection is fully established and ready for use. */
public val ConnectionState.isUsable: Boolean
    get() = this is ConnectionState.Connected

/** Whether a connection attempt is actively in progress; `false` for [ConnectionState.Disconnecting], which ends one. */
public val ConnectionState.isInProgress: Boolean
    get() =
        this is ConnectionState.Connecting ||
            this is ConnectionState.Configuring ||
            this is ConnectionState.Reconnecting

/** Human-readable status description. */
public val ConnectionState.statusMessage: String
    get() = when (this) {
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Disconnecting -> "Disconnecting"
        is ConnectionState.Connecting -> "Connecting (attempt $attempt)"
        is ConnectionState.Configuring -> "Configuring: ${phase.name} (${(progress * 100).toInt()}%)"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Reconnecting -> "Reconnecting (attempt $attempt)"
        is ConnectionState.DeviceSleeping -> "Device sleeping"
        is ConnectionState.Failed -> "Connection failed"
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
     * The request was not accepted for lack of capacity; back off before retrying.
     *
     * From `Routing.Error.RATE_LIMIT_EXCEEDED`, a full firmware transmit queue, or a full SDK paced
     * queue ([SendFailure.QueueFull]) while the link is up. A radio that is disabled or has no
     * interface returns [Failed] with `Routing.Error.NO_INTERFACE` instead, since waiting cannot help.
     */
    public data object RateLimited : AdminResult<Nothing>

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
public inline fun <T> AdminResult<T>.getOrElse(block: (AdminResult<T>) -> T): T = when (this) {
    is AdminResult.Success -> value
    else -> block(this)
}

/** Returns `true` if this is [AdminResult.Success]. */
public val <T> AdminResult<T>.isSuccess: Boolean get() = this is AdminResult.Success

/** Transforms the [Success] value with [transform], propagating failures unchanged. */
public inline fun <T, R> AdminResult<T>.map(transform: (T) -> R): AdminResult<R> = when (this) {
    is AdminResult.Success -> AdminResult.Success(transform(value))
    is AdminResult.SessionKeyExpired -> this
    is AdminResult.Unauthorized -> this
    is AdminResult.Timeout -> this
    is AdminResult.RateLimited -> this
    is AdminResult.NodeUnreachable -> this
    is AdminResult.Failed -> this
}

/** Applies [onSuccess] or [onFailure] depending on the result. */
public inline fun <T, R> AdminResult<T>.fold(onSuccess: (T) -> R, onFailure: (AdminResult<T>) -> R): R = when (this) {
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

/**
 * Returns the [Success] value or throws [AdminResultException] describing the failure.
 *
 * Useful for callers who prefer exception-based error handling over sealed-type matching.
 */
public fun <T> AdminResult<T>.getOrThrow(): T = when (this) {
    is AdminResult.Success -> value
    is AdminResult.SessionKeyExpired -> throw AdminResultException.SessionKeyExpired()
    is AdminResult.Unauthorized -> throw AdminResultException.Unauthorized()
    is AdminResult.Timeout -> throw AdminResultException.Timeout()
    is AdminResult.RateLimited -> throw AdminResultException.RateLimited()
    is AdminResult.NodeUnreachable -> throw AdminResultException.NodeUnreachable()
    is AdminResult.Failed -> throw AdminResultException.RoutingFailed(routingError)
}

/** Exception hierarchy thrown by [getOrThrow]. */
public sealed class AdminResultException(message: String) : Exception(message) {
    public class SessionKeyExpired : AdminResultException("Admin session key expired")
    public class Unauthorized : AdminResultException("Not authorized for this operation")
    public class Timeout : AdminResultException("Operation timed out waiting for device response")
    public class RateLimited : AdminResultException("Device rate-limited the request")
    public class NodeUnreachable : AdminResultException("Destination node is unreachable")
    public class RoutingFailed(public val error: Routing.Error) :
        AdminResultException("Routing error: ${error.name}")
}
