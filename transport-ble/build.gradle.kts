// transport-ble/build.gradle.kts
//
// BLE transport via JuulLabs Kable. Targets: android, jvm, ios.
// Kable ships JVM backends for macOS, Windows, and Linux; same library
// powers Meshtastic-Android.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
}

kotlin {
    // Real-hardware conformance tests (androidDeviceTest) — run on demand against an
    // advertising Meshtastic radio via `:transport-ble:connectedAndroidTest`. They
    // assume-skip when no radio is in range, so device-less runs never fail.
    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension> {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets.all {
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kable)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlinTest)
            implementation(libs.coroutinesTest)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidxTestRunner)
            implementation(libs.androidxTestRules)
            implementation(project(":storage-sqldelight"))
        }
    }
}

// Android needs BLE permissions documented in consumer manifests; we don't add
// them here because hosts may scope BLE permissions differently. See
// docs/security.md for the consumer-manifest checklist.
