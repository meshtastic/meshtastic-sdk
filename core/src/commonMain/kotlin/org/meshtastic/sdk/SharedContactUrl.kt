/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.SharedContact
import org.meshtastic.sdk.internal.base64UrlDecode
import org.meshtastic.sdk.internal.base64UrlEncode

/**
 * Utilities for encoding and parsing Meshtastic shared contact URLs.
 *
 * Contact URLs use the format: `https://meshtastic.org/v/#<base64url-encoded-proto>[?query-params]`
 */
public object SharedContactUrl {
    /** Standard URL prefix for shared contacts. */
    public const val PREFIX: String = "https://meshtastic.org/v/#"

    /**
     * Encodes a [SharedContact] proto into a shareable URL.
     */
    public fun encode(contact: SharedContact): String {
        val bytes = SharedContact.ADAPTER.encode(contact)
        return PREFIX + base64UrlEncode(bytes)
    }

    /**
     * Parses a shared contact URL into a [SharedContact].
     * Returns `null` if the URL is malformed or payload fails to decode.
     */
    public fun parse(url: String): SharedContact? {
        val trimmed = url.trim()
        val hashIdx = trimmed.indexOf('#')
        if (hashIdx < 0) return null
        val payload = trimmed.substring(hashIdx + 1).substringBefore('?')
        if (payload.isEmpty()) return null
        val bytes = base64UrlDecode(payload) ?: return null
        return runCatching { SharedContact.ADAPTER.decode(bytes) }.getOrNull()
    }
}

/** Encodes this contact into a shareable URL. */
public fun SharedContact.toUrl(): String = SharedContactUrl.encode(this)
