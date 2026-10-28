/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishingConventionPluginTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signTask = Regex("""^sign\w*Publication""", RegexOption.MULTILINE)

    private fun fixture(): ConventionPluginFixture = ConventionPluginFixture(tmp.root).apply {
        buildFile(
            """
            plugins {
                `java-platform`
                id("meshtastic.publishing")
            }
            version = "1.2.3"
            """,
        )
    }

    @Test
    fun `signs publications when signingInMemoryKey is set`() {
        val result = fixture().run("tasks", "--all", "-PsigningInMemoryKey=not-a-real-key")
        assertTrue(signTask.containsMatchIn(result.output), result.output)
    }

    @Test
    fun `does not sign publications without signingInMemoryKey`() {
        val result = fixture().run("tasks", "--all")
        assertFalse(signTask.containsMatchIn(result.output), result.output)
    }
}
