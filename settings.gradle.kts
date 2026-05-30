@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.4.1"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.6.0"
}

// Shared Develocity and Build Cache configuration
apply(from = "gradle/develocity.settings.gradle")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "meshtastic-sdk"

// Phase 1 bootstrap: keep focused module set while enabling convention plugins.
include(":core")
include(":testing")

// Phase 2: TCP transport, SQLDelight storage, and CLI sample.
include(":transport-tcp")
include(":storage-sqldelight")
include(":samples:cli")
// Sprint 6: Compose Multiplatform parity sample (Android + iOS + JVM desktop) over TCP.
include(":samples:parity-app")
include(":samples:parity-android-app")

// Phase 3: BLE transport.
include(":transport-ble")
// Phase 4: USB / native serial transport (jvm + android via jSerialComm).
include(":transport-serial")
// Bill-of-Materials: pins all radio-client-* artifacts to one version.
include(":bom")

// --- Roadmap modules (not MVP — uncomment when promoted) ---
// include(":transport-mqtt-proxy")    // Phase 6
// include(":transport-rpc")           // post-1.0
// include(":transport-http")          // post-1.0
// include(":host-rpc-server")         // post-1.0
// include(":rpc")                     // post-1.0
// include(":storage-okio-files")      // post-1.0
// include(":samples:wasm-app")        // when wasm tooling matures
// include(":samples:host-rpc-server") // post-1.0
