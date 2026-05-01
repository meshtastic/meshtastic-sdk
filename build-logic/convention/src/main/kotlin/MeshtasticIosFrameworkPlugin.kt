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
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * iOS framework + SKIE convention plugin (Sprint 6 / ADR-007).
 *
 * Applies on top of [`meshtastic.kmp.library`][KmpLibraryConventionPlugin]. Configures the three
 * iOS targets to emit a static framework named `MeshtasticSDK` and applies SKIE so Kotlin sealed
 * types, suspends, and `Flow`s bridge to natural Swift `enum` / `async throws` / `AsyncSequence`
 * constructs.
 *
 * **Framework baseName note.** ADR-007 names the *aggregated XCFramework* `RadioClient` for SPM
 * consumers; the per-module framework name is a different artifact (the `.framework` produced by
 * each `linkReleaseFramework<target>` task). We deliberately use `MeshtasticSDK` here to avoid a
 * Swift naming collision: `RadioClient.RadioClient` would conflict with the framework name and
 * SKIE would rename the public class to `RadioClient_`. The aggregated XCFramework that ships
 * to SPM consumers is built by a separate aggregator module (deferred follow-up); when that
 * lands it can keep the `RadioClient` name on the umbrella artifact.
 *
 * **Why static?** Per ADR-007, `RadioClient.xcframework` is consumed via SPM by Meshtastic-Apple.
 * Static frameworks are the SPM/CocoaPods default — they fold the Kotlin runtime + SDK code into
 * the consumer app at link time. Dynamic frameworks add a load-time cost and complicate code
 * signing on App Store builds. The cost is binary size, which we accept for a single-radio SDK.
 *
 * **Per-module frameworks today, single aggregator deferred.** Each library module that applies
 * this plugin produces its own `RadioClient` framework. The single-aggregator path that ADR-007
 * envisions ("one `RadioClient.xcframework` from :core + :transport-ble + :transport-tcp +
 * :storage-sqldelight") requires either an extra aggregator module or a relaxation of ADR-008
 * for the iOS source-set; both are deferred to a follow-up sprint that also wires KMMBridge for
 * the SPM publish step. For now, samples consume the per-module frameworks via Gradle project
 * dependencies — the SwiftUI shell only sees the Compose-app framework anyway.
 */
class MeshtasticIosFrameworkPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // SKIE pulls its own dependencies; verify the version catalog has it pinned before we
        // apply (catches "ID typo" failures at configure time, not at compile time).
        extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("skie")
            .orElseThrow { IllegalStateException("libs.versions.toml is missing 'skie' version") }

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
