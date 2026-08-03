// build-logic/settings.gradle.kts
//
// Standalone settings for the included build. Re-uses the same version catalog
// as the main build so plugin versions stay in lockstep.

@file:Suppress("UnstableApiUsage")

plugins {
    id("com.gradle.develocity") version "4.5.0"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.8.0"
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")

// Build Scans + remote Build Cache (Develocity OSS Community instance)
apply(from = "../gradle/develocity.settings.gradle")
