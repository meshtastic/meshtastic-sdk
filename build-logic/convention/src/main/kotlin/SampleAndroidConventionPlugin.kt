/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class SampleAndroidConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
        }

        // Library modules are compiled with -Xjvm-expose-boxed (experimental),
        // which marks their bytecode as pre-release. Allow consumption here.
        tasks.withType<KotlinCompilationTask<*>>().configureEach {
            compilerOptions {
                freeCompilerArgs.add("-Xskip-prerelease-check")
            }
        }

        extensions.configure<ApplicationExtension> {
            namespace = "org.meshtastic.sdk.${project.name.replace("-", ".")}"
            compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
            defaultConfig {
                applicationId = "org.meshtastic.sdk.${project.name.replace("-", ".")}"
                minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
                targetSdk = libs.findVersion("androidTargetSdk").get().requiredVersion.toInt()
                versionCode = 1
                versionName = "0.1.0"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
}
