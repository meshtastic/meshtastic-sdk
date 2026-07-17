/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

class PublishingConventionPlugin : Plugin<Project> {
    @OptIn(ExperimentalAbiValidation::class)
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.vanniktech.maven.publish")

        // Enable Kotlin Gradle Plugin's built-in ABI validation, the
        // JetBrains-supported successor to kotlinx-binary-compatibility-validator.
        // Tasks: ./gradlew checkKotlinAbi (verify, auto-bound to check)
        //        ./gradlew updateKotlinAbi (refresh reference dump).
        // KGP 2.4 unified the DSL: `abiValidation { }` on the Kotlin extension
        // both enables validation and configures it (the old per-flavor
        // extensions and their `enabled` property were removed).
        listOf("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.jvm").forEach { id ->
            plugins.withId(id) {
                extensions.getByType(KotlinProjectExtension::class).abiValidation {
                    // Hide implementation packages from the public ABI surface
                    // (e.g. SQLDelight-generated row classes / queries / database
                    // in `…internal.**`). See ADR-005 and API-P0-2.
                    filters.exclude.byNames.add("**.internal.**")
                }
            }
        }

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = false)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }

            coordinates(
                groupId = "org.meshtastic",
                artifactId = "sdk-${project.name}",
                version = project.version.toString(),
            )

            pom {
                name.set("sdk-${project.name}")
                description.set(
                    "Multiplatform Kotlin SDK for Meshtastic mesh radios — module: ${project.name}",
                )
                url.set("https://github.com/meshtastic/meshtastic-sdk")
                licenses {
                    license {
                        name.set("GNU General Public License, Version 3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/meshtastic/meshtastic-sdk")
                    connection.set("scm:git:git://github.com/meshtastic/meshtastic-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/meshtastic/meshtastic-sdk.git")
                }
                developers {
                    developer {
                        id.set("meshtastic")
                        name.set("Meshtastic Project")
                        url.set("https://meshtastic.org")
                    }
                }
            }
        }
    }
}
