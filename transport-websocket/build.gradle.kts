// transport-websocket/build.gradle.kts
//
// WebSocket PhoneAPI transport (ws:// and wss://, /api/v1/ws) via the Ktor client.
// CIO engine on JVM and Android, Darwin engine on iOS.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
}

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.ktorClientCore)
            api(libs.ktorClientWebsockets)
        }
        val jvmAndroidMain = getByName("jvmAndroidMain") {
            dependencies {
                implementation(libs.ktorClientCio)
            }
        }
        androidMain {
            dependsOn(jvmAndroidMain)
        }
        iosMain.dependencies {
            implementation(libs.ktorClientDarwin)
        }
        jvmTest.dependencies {
            implementation(libs.ktorServerCio)
            implementation(libs.ktorServerWebsockets)
        }
    }
}
