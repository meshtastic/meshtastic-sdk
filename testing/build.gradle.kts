// testing/build.gradle.kts
//
// Public test fixtures + the FakeRadioTransport replay helper. Published so
// consumers writing apps against this SDK can use the same test infrastructure.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
}

kotlin {
    sourceSets.all {
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.turbine)
            api(libs.coroutinesTest)
            implementation(libs.kotlinxIoCore)
        }
    }
}
