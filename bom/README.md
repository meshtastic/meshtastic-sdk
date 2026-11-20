# `:bom` (`sdk-bom`)

A Maven [Bill of Materials](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#bill-of-materials-bom-poms) that pins every published `org.meshtastic:sdk-*` artifact to one version. Import it once and drop the version from each SDK dependency.

All `sdk-*` artifacts are released together from one git tag and tested as a set, so the BOM is how a build stays on a tested combination. Mixing versions, such as `sdk-core:0.2.0` with `sdk-transport-ble:0.1.0`, is unsupported and can fail at runtime. Upgrading the SDK becomes a one-line change, and two libraries that both depend on `sdk-core` resolve to the BOM's version without a `resolutionStrategy`.

## Usage

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation(platform("org.meshtastic:sdk-bom:0.2.0"))

    implementation("org.meshtastic:sdk-core")
    implementation("org.meshtastic:sdk-transport-tcp")
    implementation("org.meshtastic:sdk-storage-sqldelight")

    testImplementation("org.meshtastic:sdk-testing")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation platform('org.meshtastic:sdk-bom:0.2.0')

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
      <version>0.2.0</version>
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
</dependencies>
```

## Pinned artifacts

Each artifact is constrained to the BOM's own version, which axion-release derives from the git tag. [`bom/build.gradle.kts`](./build.gradle.kts) is the source.

| Artifact | Module | Purpose |
|---|---|---|
| `org.meshtastic:sdk-core` | `:core` | `RadioClient`, the engine and the public API; re-exports the `org.meshtastic:protobufs` types. |
| `org.meshtastic:sdk-transport-ble` | `:transport-ble` | Bluetooth LE GATT transport (Kable). Android, iOS, JVM. |
| `org.meshtastic:sdk-transport-tcp` | `:transport-tcp` | TCP transport on port 4403 (Ktor) and mDNS discovery. Android, iOS, JVM. |
| `org.meshtastic:sdk-transport-websocket` | `:transport-websocket` | WebSocket phone API transport (Ktor client). Android, iOS, JVM, JS, Wasm. |
| `org.meshtastic:sdk-transport-serial` | `:transport-serial` | USB serial transport (jSerialComm, usb-serial-for-android). JVM and Android. |
| `org.meshtastic:sdk-storage-sqldelight` | `:storage-sqldelight` | Persistent `StorageProvider` backed by SQLDelight. |
| `org.meshtastic:sdk-testing` | `:testing` | `FakeRadioTransport` and `TestClock`. |

The protobuf types come from `org.meshtastic:protobufs` through `sdk-core`; the BOM doesn't pin that artifact. The per-target matrix is in [`docs/architecture/module-graph.md`](../docs/architecture/module-graph.md#mvp-per-target-compile-matrix).

## Upgrading

- Upgrade the BOM, not individual artifacts. Pinning one artifact to a different version puts the build on an untested combination.
- Don't override an `sdk-*` coordinate with `force {}` or a `resolutionStrategy {}`. If you need an unreleased fix, file an issue; a patch release is usually faster than carrying an override.
- The BOM constrains only `sdk-*` coordinates. Third-party libraries such as Kable, Ktor and SQLDelight resolve through the SDK's own dependencies or your build.

## Related

- [`README.md`](../README.md): start here.
- [`docs/integration-guide.md`](../docs/integration-guide.md): platform setup (Android manifest, Bluetooth permissions, USB host wiring, iOS `Info.plist`).
- [`docs/versioning.md`](../docs/versioning.md): SemVer and ABI policy.
