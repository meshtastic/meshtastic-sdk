/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * A throwaway build that applies this project's convention plugins. KGP and AGP are
 * compileOnly here, so a fixture cannot apply either of them.
 */
class ConventionPluginFixture(private val projectDir: File) {
    init {
        // The test task runs in build-logic/convention; the catalog is two levels up.
        val catalog = File("../../gradle/libs.versions.toml").absoluteFile.normalize()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs {
                    create("libs") { from(files("${catalog.path}")) }
                }
            }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
    }

    fun buildFile(content: String) {
        projectDir.resolve("build.gradle.kts").writeText(content.trimIndent())
    }

    fun run(vararg args: String): BuildResult = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()
}
