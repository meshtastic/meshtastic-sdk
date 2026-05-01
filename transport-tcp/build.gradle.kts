// transport-tcp/build.gradle.kts
//
// TCP/4403 PhoneAPI transport via ktor-network sockets.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
}

kotlin {
    sourceSets.all {
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktorNetwork)
        }
    }
}
