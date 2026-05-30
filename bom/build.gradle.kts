// bom/build.gradle.kts
//
// Bill-of-Materials. Pinning every sdk-* artifact to one version so
// consumers can:
//
//   implementation(platform("org.meshtastic:sdk-bom:0.1.0"))
//   implementation("org.meshtastic:sdk-core")
//   implementation("org.meshtastic:sdk-transport-ble")
//
// without listing versions on each dependency.

plugins {
    `java-platform`
    id("meshtastic.publishing")
}

dependencies {
    constraints {
        // Every published artifact in lockstep. Version comes from
        // project.version (axion-release-driven once A3 lands).
        api("org.meshtastic:sdk-core:${project.version}")
        api("org.meshtastic:sdk-transport-ble:${project.version}")
        api("org.meshtastic:sdk-transport-tcp:${project.version}")
        api("org.meshtastic:sdk-transport-serial:${project.version}")
        api("org.meshtastic:sdk-storage-sqldelight:${project.version}")
        api("org.meshtastic:sdk-testing:${project.version}")
    }
}
