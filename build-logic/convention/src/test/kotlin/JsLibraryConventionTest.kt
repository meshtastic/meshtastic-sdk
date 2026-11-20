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

class JsLibraryConventionTest {
    @Test
    fun `adds the js and wasmJs targets next to the kmp library targets`() {
        val targets = config.getValue("targets").split(",").toSet()
        assertEquals(setOf("jvm", "iosArm64", "iosX64", "iosSimulatorArm64", "js", "wasmJs"), targets)
    }

    @Test
    fun `js runs tests on nodejs only`() = assertEquals("nodejs", config.getValue("jsEnvironments"))

    @Test
    fun `wasmJs runs tests on nodejs only`() = assertEquals("nodejs", config.getValue("wasmEnvironments"))

    @Test
    fun `nodejs comes from PATH instead of a project repository`() =
        assertEquals("false,false", config.getValue("nodeDownload"))

    @Test
    fun `wasm nodejs comes from PATH instead of a project repository`() =
        assertEquals("false,false", config.getValue("wasmNodeDownload"))

    @Test
    fun `npm lock file is not tracked`() = assertEquals("NONE,false", config.getValue("npmLock"))

    @Test
    fun `wasm npm lock file is not tracked`() = assertEquals("NONE,false", config.getValue("wasmNpmLock"))

    companion object {
        private val dir: File = Files.createTempDirectory("js-library-fixture").toFile()

        private val config: Map<String, String> by lazy {
            ConventionPluginFixture(dir).run {
                buildFile(
                    """
                    import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
                    import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
                    import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
                    import org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension
                    plugins {
                        id("meshtastic.kmp.library")
                        id("meshtastic.js.library")
                    }
                    tasks.register("printConfig") {
                        doLast {
                            println("CFG:targets=" + kotlin.targets.names.filter { it != "metadata" }.joinToString(","))
                            val environments = listOfNotNull(
                                "browser".takeIf { "jsBrowserTest" in tasks.names },
                                "nodejs".takeIf { "jsNodeTest" in tasks.names },
                            )
                            println("CFG:jsEnvironments=" + environments.joinToString(","))
                            val wasmEnvironments = listOfNotNull(
                                "browser".takeIf { "wasmJsBrowserTest" in tasks.names },
                                "nodejs".takeIf { "wasmJsNodeTest" in tasks.names },
                            )
                            println("CFG:wasmEnvironments=" + wasmEnvironments.joinToString(","))
                            val download = listOf(project, rootProject).map {
                                it.extensions.getByType(NodeJsEnvSpec::class.java).download.get()
                            }
                            println("CFG:nodeDownload=" + download.joinToString(","))
                            val wasmDownload = listOf(project, rootProject).map {
                                it.extensions.getByType(WasmNodeJsEnvSpec::class.java).download.get()
                            }
                            println("CFG:wasmNodeDownload=" + wasmDownload.joinToString(","))
                            val npm = rootProject.extensions.getByType(NpmExtension::class.java)
                            println(
                                "CFG:npmLock=" + npm.packageLockMismatchReport.get() + "," + npm.reportNewPackageLock.get(),
                            )
                            val wasmNpm = rootProject.extensions.getByType(WasmNpmExtension::class.java)
                            println(
                                "CFG:wasmNpmLock=" + wasmNpm.packageLockMismatchReport.get() + "," +
                                    wasmNpm.reportNewPackageLock.get(),
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
