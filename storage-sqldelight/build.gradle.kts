// storage-sqldelight/build.gradle.kts

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
    alias(libs.plugins.sqldelight)
}

kotlin {
    js {
        browser {
            testTask { useKarma { useChromeHeadlessNoSandbox() } }
        }
    }

    sourceSets {
        val fileMain = create("fileMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(fileMain)
        androidMain.get().dependsOn(fileMain)
        appleMain.get().dependsOn(fileMain)
        jsMain.dependencies { implementation(libs.sqldelightWebWorkerDriver) }
        jsTest.dependencies {
            implementation(libs.kotlinTest)
            implementation(libs.coroutinesTest)
            implementation(devNpm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
        }

        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.sqldelightRuntime)
            implementation(libs.sqldelightAsyncExt)
            implementation(libs.okio)
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
            generateAsync.set(true)
            // Source files live at src/commonMain/sqldelight/<package>/Mesh.sq
        }
    }
}

plugins.withType(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin::class.java).configureEach {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> { download.set(false) }
}
