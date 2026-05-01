// core/build.gradle.kts

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        // :core keeps its historical Android manifest package, distinct from the
        // convention-default `org.meshtastic.kmp.core` derived from the module name.
        namespace = "org.meshtastic.sdk"
    }

    // Internal :core code freely uses our own experimental marker;
    // external consumers must opt in explicitly.
    sourceSets.all {
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":proto"))
            api(libs.coroutinesCore)
            api(libs.kotlinxIoCore)
            implementation(libs.atomicfu)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinTest)
            implementation(libs.turbine)
            implementation(libs.kotestAssertions)
            implementation(libs.coroutinesTest)
            implementation(project(":testing"))
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.coroutinesAndroid)
        }
    }
}

// ---------------------------------------------------------------------------
// Architecture enforcement (ADR-008): :core must depend on no other module
// in this build except :proto. Verified at configuration time by walking
// every Kotlin source-set dependency and rejecting any ProjectDependency
// whose path is not :proto. Hooked into `check` so CI catches drift.
// ---------------------------------------------------------------------------
val verifyModuleBoundary by tasks.registering {
    group = "verification"
    description = "Fails if :core declares a project dependency other than :proto (ADR-008)."
    val allowed = setOf(":proto")
    val violations = mutableListOf<String>()
    configurations
        .matching {
            it.name.endsWith("MainImplementation", ignoreCase = true) ||
                it.name.endsWith("MainApi", ignoreCase = true) ||
                it.name.endsWith("MainCompileOnly", ignoreCase = true) ||
                it.name.endsWith("MainRuntimeOnly", ignoreCase = true)
        }
        .configureEach {
            val configName = name
            dependencies.withType(ProjectDependency::class.java).configureEach {
                if (path !in allowed) {
                    violations += "configuration=$configName -> $path"
                }
            }
        }
    doLast {
        if (violations.isNotEmpty()) {
            throw GradleException(
                "ADR-008 violation: :core may only depend on :proto. Offending project deps:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyModuleBoundary) }
