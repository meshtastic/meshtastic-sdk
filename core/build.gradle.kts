// core/build.gradle.kts

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    id("meshtastic.ios.framework")
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
            api(libs.meshtasticProtobufs)
            api(libs.coroutinesCore)
            implementation(libs.atomicfu)
            api(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinTest)
            implementation(libs.turbine)
            implementation(libs.kotestAssertions)
            implementation(libs.coroutinesTest)
            implementation(project(":testing"))
        }
        androidMain.dependencies {
            implementation(libs.coroutinesAndroid)
        }
    }
}

// ---------------------------------------------------------------------------
// Architecture enforcement (ADR-008): :core must not depend on other in-tree
// modules. Now that proto types come from the published org.meshtastic:protobufs
// artifact, :core has zero project dependencies. Verified at configuration time.
// ---------------------------------------------------------------------------
val verifyModuleBoundary by tasks.registering {
    group = "verification"
    description = "Fails if :core declares any project dependency (ADR-008)."
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
                violations += "configuration=$configName -> $path"
            }
        }
    doLast {
        if (violations.isNotEmpty()) {
            throw GradleException(
                "ADR-008 violation: :core must not depend on other in-tree modules. " +
                    "Offending project deps:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyModuleBoundary) }
