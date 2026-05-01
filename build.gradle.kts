// build.gradle.kts — root

plugins {
    alias(libs.plugins.dokka)
    alias(libs.plugins.powerAssert) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.wire) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.axionRelease)
}

scmVersion {
    tag {
        prefix.set("v")
    }
    versionIncrementer("incrementPatch")
}

val resolvedVersion: String = scmVersion.version

allprojects {
    group = "org.meshtastic"
    version = resolvedVersion
}

dependencies {
    dokka(project(":proto"))
    dokka(project(":core"))
    dokka(project(":transport-ble"))
    dokka(project(":transport-tcp"))
    dokka(project(":transport-serial"))
    dokka(project(":storage-sqldelight"))
    dokka(project(":testing"))
}

val catalog = extensions
    .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
    .named("libs")
val ktlintVersion = catalog.findVersion("ktlint").get().requiredVersion
val licenseHeader = rootProject.file("config/spotless/license-header.txt").readText()

spotless {
    val excludes = arrayOf(
        "**/build/**",
        "**/.gradle/**",
        "**/generated/**",
        "**/node_modules/**",
        "proto/src/protobufs/**",
        "**/detekt-baseline.xml",
        "**/api/*.api",
        "gradle/wrapper/**",
        "**/*.iml",
        ".idea/**",
    )

    kotlin {
        target("**/*.kt")
        targetExclude(*excludes)
        ktlint(ktlintVersion).editorConfigOverride(
            mapOf(
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
            ),
        )
        licenseHeader(licenseHeader)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(*excludes)
        ktlint(ktlintVersion)
    }
    format("misc") {
        target(
            "**/*.md",
            "**/*.yml",
            "**/*.yaml",
            "**/*.json",
            "**/*.toml",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
        )
        targetExclude(*excludes)
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    apply(plugin = "dev.detekt")

    val subCatalog = rootProject.extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")
    val detektVersion = subCatalog.findVersion("detekt").get().requiredVersion
    val detektComposeRules = subCatalog.findLibrary("detektComposeRules").get()

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        toolVersion = detektVersion
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        ignoreFailures = false
    }

    dependencies.add("detektPlugins", detektComposeRules)
}

val libraryModules =
    setOf("proto", "core", "transport-ble", "transport-tcp", "transport-serial", "storage-sqldelight", "testing")
subprojects {
    if (name in libraryModules) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }
}
dependencies {
    libraryModules.forEach { kover(project(":$it")) }
}
