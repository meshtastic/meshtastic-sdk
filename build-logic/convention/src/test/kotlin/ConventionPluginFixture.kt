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
 * A throwaway build that applies this project's convention plugins. KGP and AGP reach the
 * fixture through the testKitPluginClasspath configuration in build.gradle.kts.
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
        System.getenv("ANDROID_HOME")?.let { sdk ->
            projectDir.resolve("local.properties").writeText("sdk.dir=$sdk\n")
        }
    }

    fun buildFile(content: String) {
        projectDir.resolve("build.gradle.kts").writeText(content.trimIndent())
    }

    /** Reads the `key=value` lines a fixture `printConfig` task wrote to the build output. */
    fun config(vararg args: String): Map<String, String> = run(*args).output.lineSequence()
        .filter { it.startsWith(CONFIG_PREFIX) }
        .map { it.removePrefix(CONFIG_PREFIX).split("=", limit = 2) }
        .associate { it[0] to it[1] }

    fun run(vararg args: String): BuildResult = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()

    companion object {
        const val CONFIG_PREFIX = "CFG:"
    }
}
