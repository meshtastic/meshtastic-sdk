/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

import com.squareup.moshi.Moshi
import com.squareup.wire.Message
import com.squareup.wire.WireJsonAdapterFactory

/**
 * Lazy singleton Moshi instance configured with [WireJsonAdapterFactory], so we can serialize
 * any generated proto [Message] into its canonical JSON form (snake_case keys, base64 bytes,
 * nested submessages, unknown fields preserved).
 *
 * This is what lets `cli nodes`, `cli packets`, etc. emit complete proto payloads inside the
 * `data` slot of the NDJSON envelope without hand-mapping every field.
 */
internal object ProtoJson {
    val moshi: Moshi = Moshi.Builder().add(WireJsonAdapterFactory()).build()

    /** Serialize a Wire-generated proto message to canonical JSON. */
    inline fun <reified T : Message<T, *>> toJson(message: T): String = moshi.adapter(T::class.java).toJson(message)

    /** Non-reified variant for the rare case where the type is dynamic. */
    fun <T : Message<T, *>> toJson(clazz: Class<T>, message: T): String = moshi.adapter(clazz).toJson(message)
}
