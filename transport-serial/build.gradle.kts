// transport-serial/build.gradle.kts
//
// USB / native serial transport for JVM and Android via jSerialComm, plus an
// Android backend on usb-serial-for-android.
//
// jSerialComm 2.x ships native libs for win/mac/linux/android-ndk in a single
// jar, and on Android opens an already-permissioned UsbDeviceConnection rather
// than touching /dev/tty (which would require root). Discovery and port-open
// differ between targets:
//   - JVM:     SerialPort.getCommPort(name) — port-name strings from sp_list_ports
//   - Android: SerialPort.fromAndroidPort(usbDeviceConnection) after the host
//              app handles the UsbManager permission Intent
// The read/write data plane and frame-resync FSM are shared via jvmAndroidMain.
//
// usb-serial-for-android is compileOnly: it lives on JitPack, not Maven Central,
// so a host opts in by adding the library and repository itself; see
// AndroidUsbSerialPorts.

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
}

kotlin {
    // Real-hardware conformance for AndroidUsbSerialPorts (androidDeviceTest): a radio on the
    // phone's USB-C, run on demand with `:transport-serial:connectedAndroidTest`; assume-skips
    // with nothing attached.
    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension> {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
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
            dependencies {
                compileOnly(libs.usbSerialForAndroid)
            }
        }
        getByName("jvmAndroidTest").dependencies {
            implementation(libs.kotlinTest)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlinTest)
            implementation(libs.coroutinesTest)
            implementation(libs.usbSerialForAndroid)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidxTestRunner)
            implementation(libs.androidxTestRules)
            implementation(libs.usbSerialForAndroid)
        }
    }
}
