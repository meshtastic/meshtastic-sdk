// samples/cli/build.gradle.kts
//
// JVM TUI sample — connects to a Meshtastic radio over TCP and renders a live
// dashboard (connection state, node list, packet log) in the terminal using
// Jake Wharton's Mosaic (Compose-based TUI runtime). JVM-only; not published.

plugins {
    id("meshtastic.sample.jvm")
    alias(libs.plugins.composeCompiler)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport-tcp"))
    implementation(project(":transport-ble"))
    implementation(project(":transport-serial"))
    implementation(project(":storage-sqldelight"))
    implementation(libs.coroutinesCore)
    implementation(libs.kable)
    implementation(libs.sqldelightSqliteDriver)
    implementation(libs.mosaicRuntime)
    implementation(libs.mosaicTerminal)
    implementation(libs.wireMoshiAdapter)
    implementation(libs.clikt)
}

application {
    mainClass.set("org.meshtastic.cli.CliKt")
}

kotlin {
    compilerOptions {
    }
}
