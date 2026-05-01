// proto/build.gradle.kts

plugins {
    id("meshtastic.kmp.library")
    id("meshtastic.android.library")
    id("meshtastic.publishing")
    alias(libs.plugins.wire)
}

kotlin {
    android { namespace = "org.meshtastic.kmp.proto" }
    sourceSets {
        commonMain.dependencies {
            api(libs.wireRuntime)
        }
    }
    // Wire-generated code triggers `Unnecessary !!` warnings we cannot fix; relax
    // -Werror for this module only (the convention plugin sets it for everyone else).
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { allWarningsAsErrors.set(false) }
            }
        }
    }
}

wire {
    kotlin {
        out = "${layout.buildDirectory.get()}/generated/wire/commonMain/kotlin"
        // Flatten oneofs into nullable properties — removes intermediate sealed classes,
        // simplifying usage and reducing binary size.
        boxOneOfsMinSize = 5000
        // Skip defensive copies of repeated/map fields on decode for better performance.
        makeImmutableCopies = false
    }
    sourcePath {
        // Root at the submodule root so that import paths like "meshtastic/channel.proto"
        // and "nanopb.proto" resolve correctly from all .proto files.
        srcDir("src/protobufs")
        // Provides google/protobuf/descriptor.proto required by nanopb.proto.
        srcDir("src/wire-includes")
    }
    // Restrict codegen to meshtastic types only — nanopb and google descriptor types
    // are schema-only (needed for import resolution, not generated as Kotlin).
    root("meshtastic.*")
    prune("meshtastic.MeshPacket#delayed")
    prune("meshtastic.MeshPacket.Delayed")
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir("${layout.buildDirectory.get()}/generated/wire/commonMain/kotlin")
}

// Wire generates protobuf sources into commonMain. Under the new single-variant
// `com.android.kotlin.multiplatform.library` plugin, the per-variant
// generateDebug/Release tasks are gone — there is a single `generateProtos`
// (or per-source-set) task. Make every compile task depend on whatever
// generation tasks exist so ordering is correct on all platforms.
afterEvaluate {
    val generationTasks = tasks.matching { it.name.startsWith("generate") && it.name.endsWith("Protos") }
    tasks.configureEach {
        val n = name
        if ((n.startsWith("compile") || n.startsWith("javaPreCompile")) &&
            !generationTasks.names.contains(n)
        ) {
            dependsOn(generationTasks)
        }
    }
}
