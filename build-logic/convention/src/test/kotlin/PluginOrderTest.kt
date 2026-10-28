/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/** Without org.jetbrains.kotlin.multiplatform applied, these conventions must configure nothing. */
class PluginOrderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun appliesAlone(pluginId: String) {
        val fixture = ConventionPluginFixture(tmp.root)
        fixture.buildFile(
            """
            plugins {
                id("$pluginId")
            }
            """,
        )
        val result = fixture.run("help")
        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome, result.output)
    }

    @Test
    fun `android library convention waits for the KMP plugin`() = appliesAlone("meshtastic.android.library")

    @Test
    fun `ios framework convention waits for the KMP plugin`() = appliesAlone("meshtastic.ios.framework")
}
