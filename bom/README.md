# `:bom` — `sdk-bom`

A Maven [Bill of Materials](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#bill-of-materials-bom-poms)
that pins every published `org.meshtastic:sdk-*` artifact to one
version. Import it once and you can drop the `:VERSION` suffix on every
individual dependency.

## Why use the BOM?

- **Version alignment.** All `sdk-*` artifacts are released
  in lockstep from the same git tag. Mixing versions (e.g. `core:0.2.0`
  with `transport-ble:0.1.0`) is unsupported and may break at runtime
  in subtle ways (ABI drift, codec mismatches, internal package moves).
- **Fewer places to bump.** Upgrading the SDK becomes a one-line change.
- **Transitive consistency.** If two libraries in your build both
  depend on `sdk-core` via the BOM, Gradle resolves them to
  the BOM-pinned version without any manual `resolutionStrategy`.

## Usage

### Gradle Kotlin DSL

```kotlin
dependencies {
    // Import the BOM exactly once.
    implementation(platform("org.meshtastic:sdk-bom:0.1.0"))

    // Then list the artifacts you actually need — versionless.
    implementation("org.meshtastic:sdk-core")
    implementation("org.meshtastic:sdk-transport-tcp")
    implementation("org.meshtastic:sdk-storage-sqldelight")

    testImplementation("org.meshtastic:sdk-testing")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation platform('org.meshtastic:sdk-bom:0.1.0')

    implementation 'org.meshtastic:sdk-core'
    implementation 'org.meshtastic:sdk-transport-ble'
    implementation 'org.meshtastic:sdk-storage-sqldelight'

    testImplementation 'org.meshtastic:sdk-testing'
}
```

### Maven

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.meshtastic</groupId>
      <artifactId>sdk-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.meshtastic</groupId>
    <artifactId>sdk-core</artifactId>
  </dependency>
  <!-- etc. -->
</dependencies>
```

## Pinned artifacts

Every artifact below is constrained to the same `project.version` as the
BOM itself (axion-release-driven from the git tag). Source of truth:
[`bom/build.gradle.kts`](./build.gradle.kts).

| Artifact                                            | Module                  | Purpose                                                   |
|-----------------------------------------------------|-------------------------|-----------------------------------------------------------|
| `org.meshtastic:sdk-proto`                 | `:proto`                | Wire-generated protobuf types (re-exported by `:core`).   |
| `org.meshtastic:sdk-core`                  | `:core`                 | `RadioClient`, engine, public API surface.                |
| `org.meshtastic:sdk-transport-ble`         | `:transport-ble`        | BLE GATT transport (Kable). Android / iOS / JVM.          |
| `org.meshtastic:sdk-transport-tcp`         | `:transport-tcp`        | TCP/4403 transport (Ktor). All targets.                   |
| `org.meshtastic:sdk-transport-serial`      | `:transport-serial`     | USB-serial transport (jSerialComm + usb-serial-for-android). JVM/Android only. |
| `org.meshtastic:sdk-storage-sqldelight`    | `:storage-sqldelight`   | Persistent `StorageProvider` backed by SQLDelight.        |
| `org.meshtastic:sdk-testing`               | `:testing`              | `FakeRadioTransport`, `InMemoryStorage`, `TestClock`.     |

For the per-target compile matrix (which artifacts ship for which
Kotlin/Native target) see
[`docs/architecture/module-graph.md`](../docs/architecture/module-graph.md#mvp-per-target-compile-matrix).

## When to upgrade the BOM vs individual artifacts

- **Always upgrade the BOM, not individual artifacts.** All
  `sdk-*` artifacts are released together from the same git
  tag and tested as a set. Pinning a single artifact to a different
  version puts you on an untested combination and is unsupported.
- **Don't override the BOM with a `force {}` or
  `resolutionStrategy {}` on a `sdk-*` coordinate.** If you
  need a fix that isn't released yet, file an issue — we can usually
  cut a patch release faster than you can chase a manual override
  through CI.
- **Third-party transitive dependencies are not pinned by the BOM.**
  We only constrain `sdk-*` coordinates; bring your own
  versions of Kable, Ktor, SQLDelight, etc. (the BOM's own
  `dependencies { ... }` lists no constraints on these — see
  [`bom/build.gradle.kts`](./build.gradle.kts)).

## Related

- [Top-level `README.md`](../README.md) — start here.
- [`docs/integration-guide.md`](../docs/integration-guide.md) — platform
  setup (Android manifest, BLE permissions, USB host wiring, iOS
  `Info.plist`).
- [`docs/versioning.md`](../docs/versioning.md) — SemVer + ABI policy
  (and the canonical BOM section).
- [`docs/architecture/module-graph.md`](../docs/architecture/module-graph.md)
  — module dependency graph and per-target compile matrix.
