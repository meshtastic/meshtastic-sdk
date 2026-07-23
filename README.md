# meshtastic-sdk

> Kotlin Multiplatform SDK for [Meshtastic](https://meshtastic.org) mesh-network radios.
> One library. Connects to Meshtastic devices over BLE, TCP, or USB-serial from Android, JVM, and iOS.
> Wasm/browser is on the roadmap — see [`docs/future/wasm-rpc-roadmap.md`](docs/future/wasm-rpc-roadmap.md).

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/org.meshtastic/sdk-core)](https://central.sonatype.com/artifact/org.meshtastic/sdk-core)
[![CI](https://github.com/meshtastic/meshtastic-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/meshtastic/meshtastic-sdk/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/meshtastic/meshtastic-sdk/branch/main/graph/badge.svg)](https://codecov.io/gh/meshtastic/meshtastic-sdk)
[![API Docs](https://img.shields.io/badge/docs-Dokka-blue)](https://meshtastic.github.io/meshtastic-sdk/)

📚 **[API Reference (Dokka)](https://meshtastic.github.io/meshtastic-sdk/)** — published from `main`.

> **Status:** pre-1.0. APIs may change between minor versions; see [`docs/versioning.md`](docs/versioning.md). Track [`docs/`](docs/) for spec evolution.

## What this is

A Kotlin library that talks to Meshtastic radios using the device's PhoneAPI (the same protocol the official `Meshtastic-Android` and `Meshtastic-Apple` apps use). It owns the wire-protocol details — handshake, NodeDB, ACK correlation, channel decryption, deferred-decrypt, retries — and exposes them as ergonomic Kotlin coroutines + flows + sealed types.

Use it to build:

- Companion apps (Android, iOS, desktop) that don't want to reinvent the protocol.
- Headless gateways and mesh telemetry collectors on JVM/server.
- (post-1.0) Web tools (wasm) via a sidecar RPC server — design parked in [`docs/future/wasm-rpc-roadmap.md`](docs/future/wasm-rpc-roadmap.md).

This SDK does **not** include UI components, navigation, or storage policy — see [`docs/decisions/000-charter.md`](docs/decisions/000-charter.md) for the explicit non-goals.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.meshtastic:sdk-core:0.1.0")
    implementation("org.meshtastic:sdk-transport-tcp:0.1.0")     // pick a transport
    implementation("org.meshtastic:sdk-storage-sqldelight:0.1.0") // pick a storage
}
```

> Released versions are published to Maven Central. For bleeding-edge builds from `main`, use the snapshot repository below.

Available transport modules: `transport-ble`, `transport-tcp`, `transport-serial` (single multiplatform module covering JVM and Android).
Available storage modules: `storage-sqldelight`. Or roll your own `StorageProvider`.
Optionally, depend on `sdk-bom` to align all module versions; see [`bom/README.md`](bom/README.md) for usage and [`docs/versioning.md`](docs/versioning.md#bom) for the versioning policy.

### Snapshot artifacts

Every push to `main` publishes `0.1.0-SNAPSHOT` (and successor versions) to the Sonatype Central snapshot repository:

```kotlin
// settings.gradle.kts (or root build.gradle.kts repositories block)
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
    implementation("org.meshtastic:sdk-core:0.1.0-SNAPSHOT")
}
```

Snapshot artifacts are mutable — rebuilt on every commit to `main`. For reproducible builds, depend on a released version (e.g. `org.meshtastic:sdk-core:0.1.0`) rather than a `-SNAPSHOT`.

Roadmap (post-1.0, non-breaking adds): `transport-mqtt-proxy`, `transport-rpc`, `host-rpc-server`, `wasmJs` browser support — see [`docs/future/wasm-rpc-roadmap.md`](docs/future/wasm-rpc-roadmap.md).

Full module matrix: [`docs/architecture/module-graph.md`](docs/architecture/module-graph.md).

## Quick start

```kotlin
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome
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

To **observe NodeDB changes** alongside sending, run the collector in its own coroutine so it doesn't block the rest of your flow. `client.nodes` never completes — collect it from a `launch { … }` (or use `take(N)` for a bounded sample):

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
                is NodeChange.WentOffline -> println("⊘ ${change.nodeId} offline")
                is NodeChange.CameOnline  -> println("● ${change.nodeId} online")
            }
        }
    }

    client.sendText("hello mesh").await()  // not blocked by the collector
    nodesJob.cancel()                      // stop observing when you're done
}
```

Notes:
- Wire-generated proto fields are snake_case (e.g. `user.long_name`, not `user.longName`).
- **On Android**, also set `AndroidContextHolder.context = applicationContext` once in your `Application.onCreate()` before constructing a `SqlDelightStorageProvider` — see the [integration guide](docs/integration-guide.md#5-storage-on-android).
- For Android BLE, USB-serial setup, and `SqlDelightStorageProvider` Android `Context` wiring, see the [integration guide](docs/integration-guide.md).

### Picking a transport

The example above wires `TcpTransport`. The other two transports plug
in identically — only the `Builder.transport(...)` line changes. Each
ships in its own artifact; pull in the one(s) you need.

```kotlin
// BLE — multiplatform (Android / iOS / JVM-desktop via Kable).
// Android: see AndroidManifest checklist + permission notes in the integration guide.
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.RadioClient

class BleQuickStartActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.all { it }) lifecycleScope.launch { connect() } }

    override fun onStart() {
        super.onStart()
        permissions.launch(arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ))
    }

    private suspend fun connect() {
        // Filter by the Meshtastic GATT service UUID in real code.
        val ad = Scanner().advertisements.first()
        val client = RadioClient.Builder()
            .transport(BleTransport(Peripheral(ad), address = ad.identifier.toString()))
            .storage(SqlDelightStorageProvider(baseDir = filesDir.absolutePath))
            .build()
        client.connect()
        client.events.collect { println(it) }
    }
}
```

Full BLE setup (manifest entries, foreground service for API 34+, iOS
`Info.plist` keys, JVM/desktop notes) is in the
[Android setup checklist](docs/integration-guide.md#android-setup-checklist)
and the per-transport [BLE section](docs/integration-guide.md#ble) of
the integration guide.

### If your first send doesn't work, check

| Symptom | Likely cause | Fix |
|---|---|---|
| `connect()` throws `MeshtasticException.TransportFailure` on TCP | Host unreachable or firmware TCP API disabled | Verify the radio's IP/hostname and that WiFi/Ethernet is enabled. See [TCP setup](docs/integration-guide.md#tcp). |
| `connect()` hangs or fails repeatedly on BLE | Device not bonded with the OS | Pair the radio in your OS Bluetooth settings before `connect()`. See [BLE platform requirements](docs/integration-guide.md#ble). |
| `JvmSerialPorts.open(...)` throws permission denied | Serial device permission not granted | On Linux, add the user to `dialout`. On Android, request USB permission first. See [Serial (USB)](docs/integration-guide.md#serial-usb). |
| `sendText` returns `SendFailure` immediately on long messages | Payload exceeds the SDK-enforced 233-byte payload limit (`DATA_PAYLOAD_LEN`) | Split the text or send a smaller payload. See [Sending messages](docs/integration-guide.md#7-sending-messages). |
| `connect()` throws `MeshtasticException.HandshakeTimeout` | `want_config_id` reply never arrived from the device | Power-cycle the radio, verify firmware ≥ 2.3, then retry. See [Build a `RadioClient`](docs/integration-guide.md#3-build-a-radioclient). |

```kotlin
// USB-serial — JVM (jSerialComm) and Android (usb-serial-for-android)
import org.meshtastic.sdk.transport.serial.JvmSerialPorts            // androidMain: AndroidSerialPorts

val portName  = JvmSerialPorts.list().first()           // e.g. "/dev/tty.usbserial-1410"
val transport = JvmSerialPorts.open(portName, baudRate = 115200)
```

```kotlin
// TCP / WiFi — all targets
import org.meshtastic.sdk.transport.tcp.TcpTransport

val transport = TcpTransport(host = "meshtastic.local", port = 4403)
```

Full per-platform setup (Android runtime permissions, foreground-service
requirements on API 34+, USB intent filters, iOS `Info.plist` keys) is
in the [integration guide](docs/integration-guide.md). For lifecycle,
DI, and R8 patterns see the [consumer guides index](docs/consumer-guides/README.md).

Full API reference: [`docs/api-reference.md`](docs/api-reference.md).

## Targets

All published modules target the same Kotlin Multiplatform target set via the shared
`meshtastic.kmp.library` convention plugin: `jvm`, `androidTarget` (`minSdk = 26`),
`iosArm64`, `iosX64`, and `iosSimulatorArm64`. Per-module behaviour is summarised below;
see [`docs/architecture/module-graph.md`](docs/architecture/module-graph.md) for the
authoritative dependency graph.

### Platform support matrix

| Module                     | JVM | Android (`minSdk 26`) | iOS Arm64 | iOS Sim Arm64 | iOS X64 | Notes                                                                 |
|----------------------------|:---:|:---------------------:|:---------:|:-------------:|:-------:|-----------------------------------------------------------------------|
| `core`                     | ✓   | ✓                     | ✓         | ✓             | ✓       | Pure-Kotlin engine; re-exports the `org.meshtastic:protobufs` types (no other deps).                 |
| `transport-ble`            | ✓¹  | ✓                     | ✓         | ✓             | ✓       | ¹ JVM uses `BlueZ`/`BleZ`-style adapter where available; see module README. |
| `transport-tcp`            | ✓   | ✓                     | ✓         | ✓             | ✓       | Built on Ktor sockets.                                                |
| `transport-serial`         | ✓   | ✓                     | —         | —             | —       | iOS targets compile (empty actuals) but no USB-serial API on iOS.     |
| `storage-sqldelight`       | ✓   | ✓                     | ✓         | ✓             | ✓       | SQLDelight native driver on iOS, JDBC on JVM/Android.                 |
| `testing`                  | ✓   | ✓                     | ✓         | ✓             | ✓       | In-memory fakes + `TestClock`; safe to use in `commonTest`.           |

✓ = supported and exercised in CI; — = not applicable (no platform API for this transport).

`wasmJs` (browser) remains on the post-1.0 roadmap — see
[`docs/future/wasm-rpc-roadmap.md`](docs/future/wasm-rpc-roadmap.md).

## Documentation

- **[`docs/SPEC.md`](docs/SPEC.md)** — master implementation plan (mission, scope, phases, locked defaults).
- **[`docs/protocol.md`](docs/protocol.md)** — wire-level protocol bible (PhoneAPI, MeshPacket, channel encryption, MQTT proxy, …).
- **[`docs/api-reference.md`](docs/api-reference.md)** — full public Kotlin signatures with KDoc.
- **[`docs/error-taxonomy.md`](docs/error-taxonomy.md)** — what throws, what returns `AdminResult`, what surfaces as `MeshEvent`.
- **[`docs/glossary.md`](docs/glossary.md)** — vocabulary (NodeNum vs NodeId, request_id vs packet_id, channel hash, …).
- **[`docs/architecture/`](docs/architecture/)** — handshake FSM, engine actor dataflow, storage, module dependency graph (Mermaid diagrams).
- **[`docs/consumer-guides/`](docs/consumer-guides/)** — host-app integration recipes (reactive lifecycle, Hilt, MVVM, R8/Proguard).
- **[`docs/decisions/`](docs/decisions/)** — ADRs (charter, API shape, tooling, licensing, multi-module rationale).
- **[`docs/roadmap.md`](docs/roadmap.md)** — Phase 2 / Phase 3 deferred items (stubs, no-ops, missing observables).
- **[`docs/versioning.md`](docs/versioning.md)** — SemVer + ABI policy.
- **[`docs/security.md`](docs/security.md)** — threat model + scope.
- **[`docs/manual-tests.md`](docs/manual-tests.md)** — device-conformance suite (CI cannot run these).
- **[`docs/ci-cd.md`](docs/ci-cd.md)** — GitHub Actions workflows.
- **[`docs/samples.md`](docs/samples.md)** — what each `samples/*` demonstrates.

## Building from source

```bash
git clone git@github.com:meshtastic/meshtastic-sdk.git
cd meshtastic-sdk
./gradlew check                      # build + test + lint + checkKotlinAbi + detekt + :core:verifyModuleBoundary
```

Requirements:

- JDK 21 (`sdk install java 21-tem`).
- Android SDK with API 36 platform if building Android targets (set `ANDROID_HOME`).
- Xcode + iOS 14+ SDK if building iOS targets (mac only).

### Runtime requirements (consumers)

- **Android API 26+ (Android 8.0 Oreo).** All `sdk-*` Android
  artifacts pin `minSdk = 26`. Apps with `minSdk < 26` will fail to
  resolve the dependency at build time, and reflection-based loaders
  on older OS versions will fail at runtime — there is no graceful
  fallback. See the
  [Android setup checklist](docs/integration-guide.md#android-setup-checklist).
- **iOS 14+** for the BLE/TCP transports.
- **JDK 17+** runtime for JVM consumers (bytecode is JDK 17, toolchain is JDK 21).

Local commands: see [`docs/ci-cd.md`](docs/ci-cd.md#local-equivalents).

## Contributing

We use the Developer Certificate of Origin (DCO). Sign every commit:

```bash
git commit -s -m "Your message"
```

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full flow.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE) and [`docs/decisions/004-licensing.md`](docs/decisions/004-licensing.md).

## Related Meshtastic projects

- [`meshtastic/firmware`](https://github.com/meshtastic/firmware) — device-side reference (read-only behavior anchor here).
- [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs) — wire schema; consumed as the published `org.meshtastic:protobufs` artifact.
- [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android), [`meshtastic/Meshtastic-Apple`](https://github.com/meshtastic/Meshtastic-Apple) — flagship apps; cross-validation references.
- [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client) — sibling KMP library for direct MQTT broker use.
