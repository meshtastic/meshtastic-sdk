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
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * iOS framework + SKIE convention plugin (ADR-007).
 *
 * Applies on top of [`meshtastic.kmp.library`][KmpLibraryConventionPlugin]. Configures the three
 * iOS targets to emit a static framework named `MeshtasticSDK` and applies SKIE so Kotlin sealed
 * types, suspends, and `Flow`s bridge to natural Swift `enum` / `async throws` / `AsyncSequence`
 * constructs.
 *
 * **Framework baseName.** Each module that applies this plugin emits its own `MeshtasticSDK`
 * framework. The name is not `RadioClient` because `RadioClient.RadioClient` would collide with
 * the framework name and SKIE would rename the public class to `RadioClient_`.
 *
 * **Why static?** Static frameworks are the SPM/CocoaPods default: they fold the Kotlin runtime
 * and SDK code into the consumer app at link time. Dynamic frameworks add a load-time cost and
 * complicate code signing on App Store builds. The cost is binary size.
 *
 * No aggregated XCFramework or SPM package is built (docs/roadmap.md R-21). Samples consume the
 * per-module frameworks through Gradle project dependencies.
 */
class MeshtasticIosFrameworkPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            pluginManager.apply("co.touchlab.skie")

            extensions.configure<KotlinMultiplatformExtension> {
                // Apply the framework declaration to each of the iOS targets. The `target` lambda
                // form is the AGP-9 / KGP-2.3 idiom (cf. JetBrains KMP-App-Template).
                listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach { iosTarget ->
                    iosTarget.binaries.framework {
                        baseName = "MeshtasticSDK"
                        isStatic = true
                    }
                }
            }
        }
    }
}
