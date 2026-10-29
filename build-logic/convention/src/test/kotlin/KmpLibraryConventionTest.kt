/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.junit.AfterClass
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One fixture build per class; each test reads a value the convention configured. */
class KmpLibraryConventionTest {
    @Test
    fun `explicit API mode is strict`() = assertEquals("Strict", config.getValue("explicitApi"))

    @Test
    fun `declares jvm and the three ios targets`() {
        val targets = config.getValue("targets").split(",").toSet()
        assertEquals(setOf("jvm", "iosArm64", "iosX64", "iosSimulatorArm64"), targets)
    }

    @Test
    fun `jvm toolchain follows the catalog javaVersion`() = assertEquals("21", config.getValue("jvmToolchain"))

    @Test
    fun `jvm compilations treat warnings as errors and expose boxed value classes`() {
        assertEquals("true", config.getValue("allWarningsAsErrors"))
        assertTrue("-Xjvm-expose-boxed" in config.getValue("jvmFreeArgs").split(","), config.toString())
    }

    @Test
    fun `power assert is scoped to test source sets`() {
        val sets = config.getValue("powerAssertSourceSets").split(",")
        assertTrue("commonTest" in sets && "jvmTest" in sets, sets.toString())
        assertTrue("commonMain" !in sets, sets.toString())
    }

    companion object {
        private val dir: File = Files.createTempDirectory("kmp-library-fixture").toFile()

        private val config: Map<String, String> by lazy {
            ConventionPluginFixture(dir).run {
                buildFile(
                    """
                    import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
                    import org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension

                    plugins {
                        id("meshtastic.kmp.library")
                    }

                    tasks.register("printConfig") {
                        doLast {
                            fun emit(k: String, v: Any?) = println("CFG:" + k + "=" + v)
                            emit("explicitApi", kotlin.explicitApi)
                            emit("targets", kotlin.targets.names.filter { it != "metadata" }.joinToString(","))
                            val jvmCompile = tasks.named("compileKotlinJvm").get() as KotlinCompile
                            emit("jvmToolchain", jvmCompile.kotlinJavaToolchain.javaVersion.get().majorVersion)
                            emit("allWarningsAsErrors", jvmCompile.compilerOptions.allWarningsAsErrors.get())
                            emit("jvmFreeArgs", jvmCompile.compilerOptions.freeCompilerArgs.get().joinToString(","))
                            emit(
                                "powerAssertSourceSets",
                                project.extensions.getByType<PowerAssertGradleExtension>().includedSourceSets.get().joinToString(","),
                            )
                        }
                    }
                    """,
                )
                config("printConfig", "-q")
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            dir.deleteRecursively()
        }
    }
}
