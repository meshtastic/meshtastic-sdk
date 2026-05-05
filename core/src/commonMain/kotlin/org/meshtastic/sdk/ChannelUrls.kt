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
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.sdk.internal.base64UrlDecode
import org.meshtastic.sdk.internal.base64UrlEncode
/** The default Pre-Shared Key (AES-128) used for the Meshtastic primary channel. */
public val DefaultPsk: ByteArray = byteArrayOf(0x01)

/**
 * Static helpers for encoding and parsing Meshtastic channel URLs (`https://meshtastic.org/e/#...`).
 */
public object ChannelUrl {
    /** The standard URL prefix for Meshtastic channel shares. */
    public const val PREFIX: String = "https://meshtastic.org/e/#"

    /** Encodes a [ChannelSet] into a base64-url-safe Meshtastic share link. */
    public fun encode(set: ChannelSet): String = PREFIX + base64UrlEncode(ChannelSet.ADAPTER.encode(set))

    /**
     * Parses a Meshtastic share link into a [ChannelSet].
     *
     * Returns `null` if the URL is malformed or its base64 payload fails to decode into a
     * valid protobuf message.
     */
    public fun parse(url: String): ChannelSet? {
        val trimmed = url.trim()
        val hashIdx = trimmed.indexOf('#')
        if (hashIdx < 0) return null
        val payload = trimmed.substring(hashIdx + 1)
        val bytes = if (payload.isEmpty()) ByteArray(0) else (base64UrlDecode(payload) ?: return null)
        return runCatching { ChannelSet.ADAPTER.decode(bytes) }.getOrNull()
    }
}

/** Converts this [ChannelSet] into a base64-url-safe Meshtastic share link. */
public fun ChannelSet.toUrl(): String = ChannelUrl.encode(this)

/** Returns a standard PRIMARY [Channel] configuration (index 0, empty name, default PSK). */
public fun Channel.Companion.default(): Channel = Channel(
    index = 0,
    settings = ChannelSettings(name = "", psk = DefaultPsk.toByteString()),
    role = Channel.Role.PRIMARY,
)

/**
 * Computes the 8-bit hash of [name] and [psk], used by firmware to identify channels on the wire.
 *
 * Mirrors the logic in `Channels::generateHash`.
 */
public fun ChannelSettings.Companion.hash(name: String, psk: ByteArray): Int {
    var code = 0
    for (b in name.encodeToByteArray()) code = code xor (b.toInt() and 0xff)
    for (b in psk) code = code xor (b.toInt() and 0xff)
    return code and 0xff
}

/**
 * Computes the DJB2 hash of a channel name. Used by some clients for channel identification
 * separate from the on-wire XOR hash.
 *
 * @param name the channel name to hash
 * @return unsigned 32-bit DJB2 hash
 */
public fun channelNameHashDjb2(name: String): UInt {
    var hash = 5381u
    for (c in name) {
        hash += (hash shl 5) + c.code.toUInt()
    }
    return hash
}
