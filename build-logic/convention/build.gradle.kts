// build-logic/convention/build.gradle.kts

plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.javaVersion.get().toInt()))
    }
}

dependencies {
    compileOnly(libs.kotlinGradlePlugin)
    compileOnly(libs.agpGradlePlugin)
    compileOnly(libs.sqldelightGradlePlugin)
    // Convention plugin applies vanniktech maven-publish AND configures its
    // extension, so its API has to be on the runtime classpath, not just
    // compile.
    implementation(libs.vanniktechMavenPublishPlugin)
    // Dokka convention plugin configures DokkaExtension at runtime — needs implementation, not compileOnly.
    implementation(libs.dokkaGradlePlugin)
    // Power-Assert — convention plugin reads PowerAssertGradleExtension to scope
    // instrumentation to test source sets. Needs runtime classpath.
    implementation(libs.kotlinPowerAssertPlugin)
    // SKIE — applied by MeshtasticIosFrameworkPlugin to the SDK library modules. Loaded as
    // implementation so the plugin is on the runtime classpath when applied.
    implementation(libs.skieGradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "meshtastic.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "meshtastic.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("publishing") {
            id = "meshtastic.publishing"
            implementationClass = "PublishingConventionPlugin"
        }
        register("sampleJvm") {
            id = "meshtastic.sample.jvm"
            implementationClass = "SampleJvmConventionPlugin"
        }
        register("iosFramework") {
            id = "meshtastic.ios.framework"
            implementationClass = "MeshtasticIosFrameworkPlugin"
        }
    }
}
