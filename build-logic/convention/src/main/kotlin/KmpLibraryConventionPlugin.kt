/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension
import java.net.URI

class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("org.jetbrains.dokka")
            apply("org.jetbrains.kotlin.plugin.power-assert")
        }

        extensions.configure<DokkaExtension> {
            moduleName.set(project.name)
            dokkaSourceSets.configureEach {
                includes.from("Module.md")
                sourceLink {
                    localDirectory.set(file("src"))
                    remoteUrl.set(
                        URI("https://github.com/meshtastic/meshtastic-sdk/tree/main/${project.name}/src"),
                    )
                    remoteLineSuffix.set("#L")
                }
            }
        }

        extensions.configure<PowerAssertGradleExtension> {
            functions.set(
                listOf(
                    "kotlin.assert",
                    "kotlin.require",
                    "kotlin.check",
                    "kotlin.test.assertTrue",
                    "kotlin.test.assertFalse",
                    "kotlin.test.assertEquals",
                    "kotlin.test.assertNotEquals",
                    "kotlin.test.assertNull",
                    "kotlin.test.assertNotNull",
                ),
            )
            includedSourceSets.set(
                listOf(
                    "commonTest",
                    "jvmTest",
                    "jvmAndroidTest",
                    "iosTest",
                    "iosArm64Test",
                    "iosX64Test",
                    "iosSimulatorArm64Test",
                    "appleTest",
                    "androidUnitTest",
                    "androidInstrumentedTest",
                ),
            )
        }

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(libs.findVersion("javaVersion").get().requiredVersion.toInt())

            explicitApi()

            jvm()
            iosArm64()
            iosX64()
            iosSimulatorArm64()

            applyDefaultHierarchyTemplate()

            sourceSets.apply {
                commonMain.dependencies {
                    implementation(libs.findLibrary("coroutinesCore").get())
                    implementation(libs.findLibrary("kotlinxDatetime").get())
                    implementation(libs.findLibrary("kermit").get())
                }
                commonTest.dependencies {
                    implementation(libs.findLibrary("kotlinTest").get())
                    implementation(libs.findLibrary("turbine").get())
                    implementation(libs.findLibrary("kotestAssertions").get())
                    implementation(libs.findLibrary("coroutinesTest").get())
                }
            }

            targets.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions {
                            allWarningsAsErrors.set(true)
                        }
                    }
                }
            }

            // -Xjvm-expose-boxed: emit non-mangled boxed accessors for value classes
            // so Java callers can use NodeId/ChannelIndex/MessageId without hashed names.
            targets.matching {
                val type = it.platformType.name
                type == "jvm" || type == "androidJvm"
            }.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions {
                            freeCompilerArgs.add("-Xjvm-expose-boxed")
                        }
                    }
                }
            }
        }
    }
}
