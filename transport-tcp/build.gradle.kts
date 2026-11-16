// transport-tcp/build.gradle.kts
//
// TCP/4403 PhoneAPI transport via ktor-network sockets, plus mDNS discovery:
// JmDNS on the JVM, NsdManager on Android, NSNetServiceBrowser on iOS.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktorNetwork)
        }
        jvmMain.dependencies {
            implementation(libs.jmdns)
        }
    }
}
