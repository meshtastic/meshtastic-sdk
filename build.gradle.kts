// build.gradle.kts — root

plugins {
    alias(libs.plugins.dokka)
    alias(libs.plugins.powerAssert) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    // Manages CHANGELOG.md, which stays hand-written: it parses and renders the
    // file and never generates an entry from a commit. `patchChangelog` is the
    // release step; `getChangelog` prints a released section for release notes.
    alias(libs.plugins.changelog)
    // axionRelease applied conditionally below — it fails to apply when this build is
    // included as a Gradle composite build because the root project is not yet available.
    alias(libs.plugins.axionRelease) apply false
}

// Only configure SCM versioning when running as a standalone build. When included as a
// composite build (gradle.parent != null), version management is unnecessary and the plugin
// errors out trying to reach the root project too early.
if (gradle.parent == null) {
    apply(plugin = "pl.allegro.tech.build.axion-release")
    configure<pl.allegro.tech.build.axion.release.domain.VersionConfig> {
        tag {
            prefix.set("v")
        }
        versionIncrementer("incrementPatch")
    }
}

val resolvedVersion: String = if (gradle.parent == null) {
    extensions.getByType<pl.allegro.tech.build.axion.release.domain.VersionConfig>().version
} else {
    "0.0.0-composite"
}

allprojects {
    group = "org.meshtastic"
    version = resolvedVersion
}

// ---------------------------------------------------------------------------
// CHANGELOG.md. The section is cut BEFORE the tag exists, and axion derives the
// version FROM tags — so between tags `resolvedVersion` is the next patch plus
// `-SNAPSHOT`. Stripping that suffix names the release a patch would produce,
// which is the common case.
//
// `-PchangelogVersion=x.y.z` overrides it for anything else — a minor, a major,
// or an rc. Axion's own `-Prelease.version` is deliberately NOT the escape hatch
// here: off the default branch it appends the branch name as a qualifier, so
// `-Prelease.version=0.2.0` on a feature branch cuts a `## [0.2.0-my-branch]`
// heading and a matching compare link. A property that means only one thing
// cannot do that.
// ---------------------------------------------------------------------------
changelog {
    version = providers.gradleProperty("changelogVersion")
        .getOrElse(resolvedVersion.removeSuffix("-SNAPSHOT"))
    repositoryUrl = "https://github.com/meshtastic/meshtastic-sdk"
    // An empty Unreleased fails the bump here, with the plugin's own message.
    // The default skips the task green and leaves no heading, which the release
    // gate would only catch one tag later.
    patchEmpty = false
    // Breaking leads, and the constitution is why: principle V requires a
    // `### Breaking` section for every pre-1.0 breaking change, so the group has
    // to exist and belongs first.
    groups = listOf("Breaking", "Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")
    // `patchChangelog` rewrites everything between the title and the first section
    // from this value, so anything that must survive a release lives here —
    // including the horizontal rule.
    introduction =
        """
        All notable changes to this project will be documented in this file.

        The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

        ---
        """.trimIndent()
}

dependencies {
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
        "**/detekt-baseline.xml",
        "**/api/*.api",
        "gradle/wrapper/**",
        "**/*.iml",
        ".idea/**",
        // Local tooling directories, not sources. `.direnv/` holds nix-direnv's
        // flake inputs, which are read-only /nix/store paths — spotless copying
        // them into build/spotless-clean fails the task outright with
        // AccessDeniedException, so `./gradlew check` cannot run at all in a
        // Nix/direnv checkout. `.claude/` holds git worktrees, which drag in a
        // second copy of the tree (and its own .direnv).
        ".direnv/**",
        ".claude/**",
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
    setOf("core", "transport-ble", "transport-tcp", "transport-serial", "storage-sqldelight", "testing")
subprojects {
    if (name in libraryModules) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }
}
dependencies {
    libraryModules.forEach { kover(project(":$it")) }
}
