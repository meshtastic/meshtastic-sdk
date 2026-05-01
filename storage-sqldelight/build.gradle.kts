// storage-sqldelight/build.gradle.kts

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets.all {
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.sqldelightRuntime)
            implementation(libs.sqldelightCoroutinesExt)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinTest)
            implementation(libs.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelightSqliteDriver)
        }
        androidMain.dependencies { implementation(libs.sqldelightAndroidDriver) }
        jvmMain.dependencies { implementation(libs.sqldelightSqliteDriver) }
        appleMain.dependencies { implementation(libs.sqldelightNativeDriver) }
    }
}

sqldelight {
    databases {
        create("MeshDatabase") {
            packageName.set("org.meshtastic.sdk.storage.sqldelight.internal")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
            // Source files live at src/commonMain/sqldelight/<package>/Mesh.sq
        }
    }
}
