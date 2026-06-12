/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.io.bytestring.ByteString
import okio.ByteString.Companion.toByteString

/*
 * The SDK's API surface deliberately spans two byte-string vocabularies:
 *
 *  - Wire-generated proto types (packet payloads, public keys, passkeys on the wire) use
 *    [okio.ByteString] — Wire's runtime type, exposed unwrapped per ADR-001 (no mirror types).
 *  - SDK-native types ([Frame], [SessionPasskey]) use [kotlinx.io.bytestring.ByteString],
 *    the multiplatform-first standard library type.
 *
 * These adapters bridge the two so consumers never have to round-trip through raw arrays.
 */

/**
 * Convert an [okio.ByteString] (Wire proto payloads) to a
 * [kotlinx.io.bytestring.ByteString][ByteString] (SDK framing/storage types).
 *
 * @since 0.2.0
 */
public fun okio.ByteString.toKotlinxByteString(): ByteString = ByteString(toByteArray())

/**
 * Convert a [kotlinx.io.bytestring.ByteString][ByteString] (SDK framing/storage types) to an
 * [okio.ByteString] (Wire proto payloads).
 *
 * @since 0.2.0
 */
public fun ByteString.toOkioByteString(): okio.ByteString = toByteArray().toByteString()
