// transport-serial/build.gradle.kts
//
// USB / native serial transport for JVM and Android via jSerialComm.
//
// jSerialComm 2.x ships native libs for win/mac/linux/android-ndk in a single
// jar, and on Android opens an already-permissioned UsbDeviceConnection rather
// than touching /dev/tty (which would require root). Discovery and port-open
// differ between targets:
//   - JVM:     SerialPort.getCommPort(name) — port-name strings from sp_list_ports
//   - Android: SerialPort.fromAndroidPort(usbDeviceConnection) after the host
//              app handles the UsbManager permission Intent
// The read/write data plane and frame-resync FSM are shared via jvmAndroidMain.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
}

kotlin {
    sourceSets.all {
    }

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
        }
        val jvmAndroidMain = getByName("jvmAndroidMain") {
            dependencies {
                implementation(libs.jSerialComm)
            }
        }
        androidMain {
            dependsOn(jvmAndroidMain)
        }
        getByName("jvmAndroidTest").dependencies {
            implementation(libs.kotlinTest)
        }
        commonTest.dependencies {
            implementation(libs.kotlinTest)
        }
    }
}
