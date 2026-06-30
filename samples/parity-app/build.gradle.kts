/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
//
// :samples:parity-app — Compose Multiplatform sample with one shared UI on
// Android + iOS + JVM desktop. Drives the SDK over TCP (PhoneAPI port 4403).
// TCP is intentional: no platform permissions, no Bluetooth dependency,
// trivially demo-able from a laptop on the same WiFi as a device.
//
// Build:
//   ./gradlew :samples:parity-android-app:assembleDebug           # Android APK
//   ./gradlew :samples:parity-app:linkReleaseFrameworkIosArm64    # iOS framework
//   ./gradlew :samples:parity-app:run                             # JVM desktop
//
// iOS framework name is `ComposeApp` (consumed by samples/parity-app/iosApp/).

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("meshtastic.android.library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop")

    // The SDK modules currently emit metadata flagged as pre-release under AGP 9 + Kotlin 2.3.x.
    // Keep this sample-only workaround until upstream tooling no longer requires it.
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xskip-prerelease-check")
                }
            }
        }
    }

    // No iosX64: Compose Multiplatform 1.11.x dropped Apple x86_64 support (the
    // iosX64/macosX64 klib variants are no longer published — Kotlin deprecated those
    // targets too), so only the arm64 device + Apple Silicon simulator targets remain.
    // CI's `test-ios` job builds/tests iosSimulatorArm64 only, so this is a no-op there.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            linkerOpts("-lsqlite3")
            // The Swift shell only talks to the Compose UI entry point; the SDK types it
            // happens to receive (e.g. ConnectionState in the controller's StateFlow) are
            // bridged transitively through the framework. No `export(project(":core"))` —
            // the SwiftUI sample doesn't construct SDK types directly.
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":transport-tcp"))
            implementation(project(":storage-sqldelight"))
            implementation(libs.coroutinesCore)
            implementation(libs.kermit)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.lifecycleRuntimeCompose)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.meshtastic.sample.MainKt"
    }
}
