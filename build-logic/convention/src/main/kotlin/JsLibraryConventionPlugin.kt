/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.LockFileMismatchReport
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension

class JsLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                js {
                    nodejs()
                }
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    nodejs()
                }
            }
            plugins.withType(NodeJsPlugin::class.java).configureEach {
                extensions.configure<NodeJsEnvSpec> { download.set(false) }
            }
            plugins.withType(WasmNodeJsPlugin::class.java).configureEach {
                extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
            }
            rootProject.plugins.withType(WasmNodeJsRootPlugin::class.java).configureEach {
                rootProject.extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
                rootProject.extensions.configure<WasmNpmExtension> {
                    packageLockMismatchReport.set(LockFileMismatchReport.NONE)
                    reportNewPackageLock.set(false)
                }
            }
            rootProject.plugins.withType(NodeJsRootPlugin::class.java).configureEach {
                rootProject.extensions.configure<NodeJsEnvSpec> { download.set(false) }
                rootProject.extensions.configure<NpmExtension> {
                    packageLockMismatchReport.set(LockFileMismatchReport.NONE)
                    reportNewPackageLock.set(false)
                }
            }
        }
    }
}
