# meshtastic-sdk

Kotlin Multiplatform SDK for [Meshtastic](https://meshtastic.org) nodes. It connects to a node over Bluetooth LE, TCP or USB serial from Android, JVM and iOS, and over the node's WebSocket phone API from those targets and from browser or Node.js hosts (Kotlin/JS and Kotlin/Wasm).

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/org.meshtastic/sdk-core)](https://central.sonatype.com/artifact/org.meshtastic/sdk-core)
[![CI](https://github.com/meshtastic/meshtastic-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/meshtastic/meshtastic-sdk/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/meshtastic/meshtastic-sdk/branch/main/graph/badge.svg)](https://codecov.io/gh/meshtastic/meshtastic-sdk)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://community.develocity.cloud/scans?search.rootProjectNames=meshtastic-sdk)
[![API Docs](https://img.shields.io/badge/docs-Dokka-blue)](https://meshtastic.github.io/meshtastic-sdk/)

The [API reference (Dokka)](https://meshtastic.github.io/meshtastic-sdk/) is published from `main`. The SDK is pre-1.0, so APIs can change between minor versions; see [`docs/versioning.md`](docs/versioning.md).

## What this is

A Kotlin library that talks to Meshtastic nodes over the PhoneAPI, the protocol the official Android and Apple clients use. It owns the wire-protocol details (handshake, NodeDB, ACK correlation, channel decryption, retries) and exposes them as coroutines, flows and sealed types.

Use it to build:

- Companion apps for Android, iOS and desktop.
- Headless gateways and telemetry collectors on the JVM.
- Browser and Node.js tools on Kotlin/JS or Kotlin/Wasm, running the engine in-process over `transport-websocket`.

The SDK has no UI components, navigation or storage policy. [`docs/decisions/000-charter.md`](docs/decisions/000-charter.md) lists the non-goals.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.meshtastic:sdk-core:0.2.0")
    implementation("org.meshtastic:sdk-transport-tcp:0.2.0")     // pick a transport
    implementation("org.meshtastic:sdk-storage-sqldelight:0.2.0") // pick a storage
}
```

Transport modules: `transport-ble`, `transport-tcp`, `transport-serial` (JVM and Android; on Android, jSerialComm or usb-serial-for-android) and `transport-websocket`. Storage: `storage-sqldelight`, `InMemoryStorageProvider` from `core` when nothing has to outlive the process, or your own `StorageProvider`. The `sdk-bom` platform aligns every module version; see [`bom/README.md`](bom/README.md).

API marked `@since 0.3.0` is in snapshots only until 0.3.0 is released. That covers `transport-websocket`, the JS and Wasm klibs, and the discovery APIs.

### Snapshot artifacts

Every push to `main` publishes the patch after the latest release as `-SNAPSHOT` (`0.2.1-SNAPSHOT` after `0.2.0`) to the Sonatype Central snapshot repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("org.meshtastic:sdk-core:0.2.1-SNAPSHOT")
}
```

Snapshots are rebuilt on every commit to `main`. Depend on a released version for reproducible builds.

The full module matrix is in [`docs/architecture/module-graph.md`](docs/architecture/module-graph.md). Deferred modules are tracked in [`docs/roadmap.md`](docs/roadmap.md): `transport-mqtt-proxy` is R-11, and the remote-RPC modules (`transport-rpc`, `host-rpc-server`) are R-12, designed in [`docs/future/wasm-rpc-roadmap.md`](docs/future/wasm-rpc-roadmap.md).

## Quick start

```kotlin
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.transport.tcp.TcpTransport

suspend fun run() {
    val client = RadioClient.Builder()
        .transport(TcpTransport(host = "meshtastic.local", port = 4403))
        .storage(SqlDelightStorageProvider(baseDir = "/tmp"))   // empty string = in-memory
        .build()

    client.connect()                                   // throws MeshtasticException on failure

    val handle = client.sendText("hello mesh")
    when (val outcome = handle.await()) {              // suspends until terminal
        SendOutcome.Success    -> println("acked or rebroadcast heard")
        is SendOutcome.Failure -> println("failed: ${outcome.reason}")
    }
    // Or observe progress:
    //   handle.state.collect { println(it) }   // Queued → Sent → Acked/Delivered/Failed
}
```

`client.nodes` never completes, so collect it in its own coroutine to observe NodeDB changes while sending:

```kotlin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.RadioClient

suspend fun observeAndSend(client: RadioClient) = coroutineScope {
    val nodesJob = launch {
        client.nodes.collect { change ->
            when (change) {
                is NodeChange.Snapshot -> println("seeded with ${change.nodes.size} nodes")
                is NodeChange.Added    -> println("+ ${change.node.user?.long_name}")
                is NodeChange.Updated  -> println("~ ${change.node.user?.long_name} (${change.changed})")
                is NodeChange.Removed  -> println("- ${change.nodeId}")
                is NodeChange.WentOffline -> println("${change.nodeId} offline")
                is NodeChange.CameOnline  -> println("${change.nodeId} online")
            }
        }
    }

    client.sendText("hello mesh").await()  // not blocked by the collector
    nodesJob.cancel()
}
```

Wire-generated proto fields are snake_case (`user.long_name`, not `user.longName`). On Android, set `AndroidContextHolder.context = applicationContext` in `Application.onCreate()` before constructing a `SqlDelightStorageProvider`; the [integration guide](docs/integration-guide.md#4-storage) covers this and the Bluetooth and USB setup.

### Picking a transport

Only the `Builder.transport(...)` line changes between transports. Each ships in its own artifact.

```kotlin
// Bluetooth LE: Android, iOS and JVM desktop, through Kable.
// The caller holds the Bluetooth permissions; see the integration guide.
import kotlinx.coroutines.flow.first
import org.meshtastic.sdk.transport.ble.BleScanner
import org.meshtastic.sdk.transport.ble.BleTransport

suspend fun firstBleNode(): BleTransport = BleTransport(BleScanner.scan().first())
```

```kotlin
// USB serial: JVM (jSerialComm). Android uses AndroidSerialPorts or AndroidUsbSerialPorts.
import org.meshtastic.sdk.transport.serial.JvmSerialPorts

val portName  = JvmSerialPorts.findMeshtastic().first().name   // ports with a Meshtastic USB vendor ID
val transport = JvmSerialPorts.open(portName, baudRate = 115200)
```

```kotlin
// TCP: Android, iOS and JVM
import org.meshtastic.sdk.transport.tcp.TcpTransport

val transport = TcpTransport(host = "meshtastic.local", port = 4403)
```

```kotlin
// WebSocket phone API: every target, including JS and Wasm
import org.meshtastic.sdk.transport.websocket.WebSocketTransport

val transport = WebSocketTransport("ws://meshtastic.local")
```

Discovery lives with each transport: `BleScanner.scan()` in `transport-ble`, mDNS through `TcpDiscovery` in `transport-tcp`, and `findMeshtastic()` with `isLikelyMeshtastic()` in `transport-serial`. `TransportTarget.parse` in `core` reads a `ble:`, `tcp:` or `serial:` spec string, the form the [CLI sample](samples/cli/README.md) takes. Each module's `Module.md` has the details.

The [integration guide](docs/integration-guide.md) has per-platform setup: Android runtime permissions, foreground services on API 34+, USB intent filters and iOS `Info.plist` keys. The [error-handling guide](docs/consumer-guides/error-handling.md) covers what to retry and what to surface. The full API is in [`docs/api-reference.md`](docs/api-reference.md).

### If your first send fails

| Symptom | Likely cause | Fix |
|---|---|---|
| `connect()` throws `MeshtasticException.Transport` on TCP | Host unreachable, or the node's network API is off | Check the node's IP or hostname and that Wi-Fi or Ethernet is enabled. See [TCP setup](docs/integration-guide.md#tcp). |
| `connect()` hangs or fails repeatedly over Bluetooth | The node isn't bonded with the OS | Pair the node in the OS Bluetooth settings first. See [Bluetooth](docs/integration-guide.md#bluetooth). |
| `JvmSerialPorts.open(...)` fails with permission denied | Serial device permission not granted | On Linux, add the user to `dialout`. On Android, request USB permission first. See [Serial](docs/integration-guide.md#serial). |
| `sendText` throws `MeshtasticException.PayloadTooLarge` | The text is over the 233-byte payload limit (`DATA_PAYLOAD_LEN`) | Split the text. See [Sending messages](docs/integration-guide.md#6-sending-messages). |
| `connect()` throws `MeshtasticException.HandshakeTimeout` | The `want_config_id` reply never arrived | Power-cycle the node, check its firmware version, then retry. See [Build a `RadioClient`](docs/integration-guide.md#2-build-a-radioclient). |

## Targets

Every published module builds `jvm`, `androidTarget` (`minSdk = 26`), `iosArm64`, `iosX64` and `iosSimulatorArm64` through the `meshtastic.kmp.library` convention plugin. `core`, `testing` and `transport-websocket` also publish `js` and `wasmJs` klibs through `meshtastic.js.library` ([ADR-017](docs/decisions/017-web-targets.md)). [`docs/architecture/module-graph.md`](docs/architecture/module-graph.md) has the dependency graph.

| Module                     | JVM | Android (`minSdk 26`) | iOS Arm64 | iOS Sim Arm64 | iOS X64 | JS  | Wasm | Notes |
|----------------------------|:---:|:---------------------:|:---------:|:-------------:|:-------:|:---:|:----:|-------|
| `core`                     | ✓   | ✓                     | ✓         | ✓             | ✓       | ✓   | ✓    | Transport-agnostic engine; re-exports the `org.meshtastic:protobufs` types. |
| `transport-ble`            | ✓   | ✓                     | ✓         | ✓             | ✓       | —   | —    | Kable; on the JVM through its btleplug backend (CoreBluetooth, BlueZ, WinRT). |
| `transport-tcp`            | ✓   | ✓                     | ✓         | ✓             | ✓       | —   | —    | Ktor sockets; mDNS discovery per platform. |
| `transport-websocket`      | ✓   | ✓                     | ✓         | ✓             | ✓       | ✓   | ✓    | Ktor client (CIO on JVM and Android, Darwin on iOS, the platform `WebSocket` on JS and Wasm). Needs firmware#12412 (ESP32) or #12413 (meshtasticd), both unmerged; plain `ws://` on meshtasticd needs #12414. |
| `transport-serial`         | ✓   | ✓                     | —         | —             | —       | —   | —    | Android: jSerialComm, or usb-serial-for-android (`compileOnly`, the host adds it). The iOS klibs carry only the common types; iOS has no serial backend. |
| `storage-sqldelight`       | ✓   | ✓                     | ✓         | ✓             | ✓       | —   | —    | SQLDelight: JDBC driver on the JVM, Android driver on Android, native driver on iOS. |
| `testing`                  | ✓   | ✓                     | ✓         | ✓             | ✓       | ✓   | ✓    | `FakeRadioTransport` and `TestClock` for `commonTest`. |

✓ means built and tested in CI. — means no artifact for that target, or no platform API for that transport.

The JS and Wasm klibs (`org.meshtastic:sdk-core-js`, `org.meshtastic:sdk-core-wasm-js`) are compiled into the consumer's own bundle, Compose Multiplatform for web included. `transport-websocket` reaches a node from either, and a host can supply its own `RadioTransport` (Web Bluetooth, Web Serial). CI tests both targets on Node.js, not in a browser.

## Documentation

- [`docs/protocol.md`](docs/protocol.md): wire protocol (PhoneAPI, MeshPacket, channel encryption, MQTT proxy).
- [`docs/api-reference.md`](docs/api-reference.md): public Kotlin signatures.
- [`docs/error-taxonomy.md`](docs/error-taxonomy.md): what throws, what returns `AdminResult`, what surfaces as `MeshEvent`.
- [`docs/glossary.md`](docs/glossary.md): NodeNum and NodeId, `request_id` and `packet_id`, channel hash.
- [`docs/architecture/`](docs/architecture/): handshake FSM, engine actor, storage and module graph.
- [`docs/integration-guide.md`](docs/integration-guide.md): dependencies, per-transport setup, storage and lifecycle in a host app.
- [`docs/consumer-guides/error-handling.md`](docs/consumer-guides/error-handling.md): what to retry and what to surface.
- [`docs/decisions/`](docs/decisions/): ADRs.
- [`docs/roadmap.md`](docs/roadmap.md): deferred items.
- [`docs/versioning.md`](docs/versioning.md): SemVer and ABI policy.
- [`docs/security.md`](docs/security.md): threat model and scope.
- [`docs/manual-tests.md`](docs/manual-tests.md): device conformance suite that CI can't run.
- [`docs/ci-cd.md`](docs/ci-cd.md): GitHub Actions workflows.
- [`docs/samples.md`](docs/samples.md): what each sample demonstrates.

## Building from source

```shell
git clone git@github.com:meshtastic/meshtastic-sdk.git
cd meshtastic-sdk
./gradlew check
```

`check` builds and runs the tests, Spotless, detekt, `checkKotlinAbi` and `:core:verifyModuleBoundary`. It needs:

- JDK 21.
- The Android SDK platform named by `androidCompileSdk` in `gradle/libs.versions.toml`, with `ANDROID_HOME` set.
- Node.js on `PATH` for the `js` and `wasmJs` targets; Gradle doesn't download it.
- Xcode on macOS for the iOS targets.

[`docs/ci-cd.md`](docs/ci-cd.md#local-equivalents) lists the targeted commands.

### Runtime requirements

- Android API 26 (Android 8.0) or later. Every Android artifact sets `minSdk = 26`, so an app with a lower `minSdk` fails to resolve the dependency. See [Platform support](docs/integration-guide.md#platform-support).
- iOS 14 or later for the Bluetooth and TCP transports.
- A JDK 17 or later runtime on the JVM. The bytecode targets JDK 17; the build toolchain is JDK 21.
- kotlinx-coroutines 1.11.0 or later. The SDK compiles against the `coroutines` version in `gradle/libs.versions.toml`, and a newer pin in your build wins at resolution.

## Contributing

Sign every commit with the Developer Certificate of Origin (DCO):

```shell
git commit -s -m "Your message"
```

[`CONTRIBUTING.md`](CONTRIBUTING.md) has the full flow.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE) and [`docs/decisions/004-licensing.md`](docs/decisions/004-licensing.md).

## Related Meshtastic projects

- [`meshtastic/firmware`](https://github.com/meshtastic/firmware): the device-side reference for protocol behavior.
- [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs): the wire schema, consumed as the published `org.meshtastic:protobufs` artifact.
- [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android) and [`meshtastic/Meshtastic-Apple`](https://github.com/meshtastic/Meshtastic-Apple): the official clients, used for cross-validation.
- [`meshtastic/MQTTastic-Client-KMP`](https://github.com/meshtastic/MQTTastic-Client-KMP): sibling KMP library for direct MQTT broker use.
