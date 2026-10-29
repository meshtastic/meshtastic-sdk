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

class IosFrameworkConventionTest {
    @Test
    fun `every ios target emits a static MeshtasticSDK framework`() {
        val frameworks = config.getValue("frameworks").split(",").toSet()
        assertEquals(
            setOf(
                "iosArm64:MeshtasticSDK:true",
                "iosX64:MeshtasticSDK:true",
                "iosSimulatorArm64:MeshtasticSDK:true",
            ),
            frameworks,
        )
    }

    @Test
    fun `applies SKIE`() = assertEquals("true", config.getValue("skie"))

    companion object {
        private val dir: File = Files.createTempDirectory("ios-framework-fixture").toFile()

        private val config: Map<String, String> by lazy {
            ConventionPluginFixture(dir).run {
                buildFile(
                    """
                    import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
                    import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

                    plugins {
                        id("meshtastic.kmp.library")
                        id("meshtastic.ios.framework")
                    }

                    tasks.register("printConfig") {
                        doLast {
                            val frameworks = kotlin.targets.withType<KotlinNativeTarget>().flatMap { t ->
                                t.binaries.filterIsInstance<Framework>().map { t.name + ":" + it.baseName + ":" + it.isStatic }
                            }
                            println("CFG:frameworks=" + frameworks.joinToString(","))
                            println("CFG:skie=" + project.pluginManager.hasPlugin("co.touchlab.skie"))
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
