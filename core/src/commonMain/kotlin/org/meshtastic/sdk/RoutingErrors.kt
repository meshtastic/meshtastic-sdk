/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Routing
import org.meshtastic.sdk.SendFailure

/**
 * Human-readable, action-hinting message for a wire-level [Routing.Error]. Suitable for direct
 * surfacing in logs, snackbars, or developer tools without further translation.
 */
public fun Routing.Error.actionableMessage(): String = when (this) {
    Routing.Error.NONE -> "No routing error reported."

    Routing.Error.NO_ROUTE ->
        "No route to destination — the recipient may be offline or unreachable. Try again later."

    Routing.Error.GOT_NAK -> "Recipient explicitly rejected the packet (NAK). Verify the destination accepts this port."

    Routing.Error.TIMEOUT -> "The mesh did not produce a response in time. Retry or wait for conditions to improve."

    Routing.Error.NO_INTERFACE -> "Device has no usable radio interface for this transmission."

    Routing.Error.MAX_RETRANSMIT ->
        "Device exhausted its retransmit budget. Move closer or wait for the link to recover."

    Routing.Error.NO_CHANNEL -> "Recipient does not share this channel — verify the channel name/PSK matches."

    Routing.Error.TOO_LARGE -> "Payload exceeds the device limit; split it into smaller messages."

    Routing.Error.NO_RESPONSE -> "Recipient did not respond. Retry later."

    Routing.Error.DUTY_CYCLE_LIMIT -> "Duty-cycle limit hit; the device must wait before transmitting again."

    Routing.Error.BAD_REQUEST ->
        "Device rejected the request as malformed. Check payload encoding and admin parameters."

    Routing.Error.NOT_AUTHORIZED -> "Operation not authorized — admin passkey or PKI session may be missing."

    Routing.Error.PKI_FAILED -> "PKI verification failed. Re-exchange public keys or disable PKI for this peer."

    Routing.Error.PKI_UNKNOWN_PUBKEY -> "Recipient's public key is unknown — request a NodeInfo refresh first."

    Routing.Error.ADMIN_BAD_SESSION_KEY -> "Admin session key invalid; refresh the admin session and retry."

    Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED -> "Admin public key not authorized on the target device."

    Routing.Error.RATE_LIMIT_EXCEEDED -> "Rate limit exceeded; back off before retrying."

    Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY -> "PKI send failed because no usable public key is available for this peer."
}

/**
 * Tag categorising the recommended remediation for a [Routing.Error]. Returns `null`
 * when no error was reported (`NONE`).
 *
 * Tags: `"check_channel"`, `"check_pki"`, `"check_admin"`, `"shrink_payload"`, `"retry"`,
 * `"wait_for_ack"`, `"unknown"`.
 */
public fun Routing.Error.suggestedAction(): String? = when (this) {
    Routing.Error.NONE -> null

    Routing.Error.NO_CHANNEL -> "check_channel"

    Routing.Error.PKI_FAILED,
    Routing.Error.PKI_UNKNOWN_PUBKEY,
    Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY,
    -> "check_pki"

    Routing.Error.NOT_AUTHORIZED,
    Routing.Error.ADMIN_BAD_SESSION_KEY,
    Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED,
    -> "check_admin"

    Routing.Error.TOO_LARGE -> "shrink_payload"

    Routing.Error.NO_ROUTE,
    Routing.Error.GOT_NAK,
    Routing.Error.TIMEOUT,
    Routing.Error.NO_INTERFACE,
    Routing.Error.MAX_RETRANSMIT,
    Routing.Error.NO_RESPONSE,
    -> "retry"

    Routing.Error.DUTY_CYCLE_LIMIT,
    Routing.Error.RATE_LIMIT_EXCEEDED,
    -> "wait_for_ack"

    Routing.Error.BAD_REQUEST -> "unknown"
}

/** Plain-language message for any [SendFailure]. Never empty. */
public fun SendFailure.humanMessage(): String = when (this) {
    SendFailure.NoRoute -> "No route to destination — recipient may be offline."
    SendFailure.MaxRetransmit -> "Device exhausted its retransmit budget."
    SendFailure.Timeout -> "Operation timed out waiting for a response."
    SendFailure.DutyCycleLimit -> "Duty-cycle limit hit; wait before retrying."
    SendFailure.Disconnected -> "Transport disconnected before send completed."
    SendFailure.HandshakeFailed -> "Send aborted because the handshake never completed."
    SendFailure.Cancelled -> "Send was cancelled before transmission."
    SendFailure.IdCollision -> "A send with this id is already in flight; refusing to overwrite it."
    SendFailure.AckTimeout -> "No ACK received within the configured send timeout."
    is SendFailure.QueueRejected -> "Device transmit queue rejected the packet (res=$res); back off and retry."
    is SendFailure.Other -> routingError.actionableMessage()
    is SendFailure.Unknown -> "Unknown send failure: $message"
}
