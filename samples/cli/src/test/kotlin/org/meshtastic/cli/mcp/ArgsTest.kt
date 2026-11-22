/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArgsTest {
    private fun args(json: String) = Args(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun stringRejectsNonStringValues() {
        assertFailsWith<ToolFailure> { args("""{"n":false}""").string("n") }
        assertFailsWith<ToolFailure> { args("""{"n":5}""").string("n") }
        assertEquals("x", args("""{"n":"x"}""").string("n"))
    }

    @Test
    fun stringsRejectsNonStringElements() {
        assertFailsWith<ToolFailure> { args("""{"n":["a",1]}""").strings("n") }
        assertFailsWith<ToolFailure> { args("""{"n":true}""").strings("n") }
        assertEquals(listOf("a"), args("""{"n":"a"}""").strings("n"))
    }
}
