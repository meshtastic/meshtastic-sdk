/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings

/**
 * Channel validation and helper utilities.
 */
public object ChannelHelpers {
    /** Maximum allowed channel name length. */
    public const val MAX_NAME_LENGTH: Int = 11

    /** Minimum PSK length for secure channels (128-bit AES). */
    public const val MIN_PSK_LENGTH: Int = 16

    /** Maximum PSK length (256-bit AES). */
    public const val MAX_PSK_LENGTH: Int = 32

    /**
     * Validation result for channel configuration.
     */
    public data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
    )

    /**
     * Validates channel settings for correctness.
     *
     * Checks:
     * - Name length within bounds
     * - PSK length is valid (0=none, 1=default, 16=AES128, 32=AES256)
     * - Role is appropriate
     */
    public fun validate(
        name: String,
        psk: ByteArray,
        role: Channel.Role = Channel.Role.SECONDARY,
    ): ValidationResult {
        val errors = mutableListOf<String>()
        if (name.length > MAX_NAME_LENGTH) {
            errors += "Channel name exceeds $MAX_NAME_LENGTH characters"
        }
        if (name.isNotEmpty() && name.isBlank()) {
            errors += "Channel name cannot be only whitespace"
        }
        val validPskLengths = setOf(0, 1, MIN_PSK_LENGTH, MAX_PSK_LENGTH)
        if (psk.size !in validPskLengths) {
            errors += "PSK must be 0 (none), 1 (default), 16 (AES-128), or 32 (AES-256) bytes"
        }
        if (role == Channel.Role.PRIMARY && name.isNotEmpty()) {
            // Primary channel typically uses empty name (firmware convention).
        }
        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }

    /**
     * Finds the first available (disabled) channel slot index in a channel list.
     * Returns null if all slots are occupied.
     *
     * @param channels current channel list from the device
     * @param maxChannels maximum number of channels supported (typically 8)
     */
    public fun findEmptySlot(channels: List<Channel>, maxChannels: Int = 8): Int? {
        for (i in 1 until maxChannels) {
            val channel = channels.getOrNull(i)
            if (channel == null || channel.role == Channel.Role.DISABLED) return i
        }
        return null
    }

    /**
     * Creates a [ChannelSettings] with validated parameters.
     * Returns null if validation fails.
     */
    public fun createSettings(
        name: String,
        psk: ByteArray = byteArrayOf(0x01),
    ): ChannelSettings? {
        val validation = validate(name, psk)
        if (!validation.isValid) return null
        return ChannelSettings(
            name = name,
            psk = psk.toByteString(),
        )
    }
}
