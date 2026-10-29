/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plugin order must not matter: the Android config waits for the KMP plugin via withPlugin. */
class AndroidLibraryConventionTest {
    @Test
    fun `android applied before kmp still configures the namespace`() =
        assertEquals("org.meshtastic.sdk.fixture", androidFirst.getValue("namespace"))

    @Test
    fun `android applied before kmp still configures sdk levels`() {
        assertEquals("37", androidFirst.getValue("compileSdk"))
        assertEquals("26", androidFirst.getValue("minSdk"))
    }

    @Test
    fun `android applied after kmp configures the same values`() = assertEquals(androidFirst, kmpFirst)

    companion object {
        private val dirs = mutableListOf<File>()

        @BeforeClass
        @JvmStatic
        fun requiresSdk() {
            val hasSdk = System.getenv("ANDROID_HOME") != null
            // CI must run these, so a missing SDK there fails instead of skipping.
            check(hasSdk || System.getenv("CI") == null) { "ANDROID_HOME not set on CI" }
            assumeTrue("ANDROID_HOME not set", hasSdk)
        }

        private fun build(plugins: String): Map<String, String> {
            val dir = Files.createTempDirectory("android-library-fixture").toFile().also(dirs::add)
            return ConventionPluginFixture(dir).run {
                buildFile(
                    """
                    import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension

                    plugins {
                        $plugins
                    }

                    tasks.register("printConfig") {
                        doLast {
                            val android = (project.extensions.getByName("kotlin") as ExtensionAware)
                                .extensions.getByType<KotlinMultiplatformAndroidLibraryExtension>()
                            println("CFG:namespace=" + android.namespace)
                            println("CFG:compileSdk=" + android.compileSdk)
                            println("CFG:minSdk=" + android.minSdk)
                        }
                    }
                    """.replace("\$plugins", plugins),
                )
                config("printConfig", "-q")
            }
        }

        private val androidFirst: Map<String, String> by lazy {
            build("id(\"meshtastic.android.library\")\n id(\"meshtastic.kmp.library\")")
        }

        private val kmpFirst: Map<String, String> by lazy {
            build("id(\"meshtastic.kmp.library\")\n id(\"meshtastic.android.library\")")
        }

        @AfterClass
        @JvmStatic
        fun cleanup() = dirs.forEach { it.deleteRecursively() }
    }
}
