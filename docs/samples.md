# Sample apps

The apps under `samples/` consume `meshtastic-sdk` the way a host app does. They aren't published. Per [ADR-006](./decisions/006-multi-module-rationale.md) the samples depend on the SDK and the SDK never depends on them.

## Inventory

| Sample | Target | Demonstrates |
|---|---|---|
| [`samples/cli`](#cli) | JVM (TUI and headless) | Bluetooth LE, TCP and serial; the full handshake; a Mosaic dashboard; headless reconnect probes for regression testing; the pre-release conformance sweep |
| [`samples/parity-app`](#parity-app) | Android, iOS, JVM desktop | One shared Compose Multiplatform UI consuming the SDK over TCP, proving the public surface compiles and renders on all three hosts. SKIE bridges the iOS framework. |
| [`samples/parity-android-app`](#parity-android-app) | Android | The Android application shell around `parity-app`, which keeps the APK separate from the KMP shared-UI module |

## `cli`

One Kotlin/JVM binary with a Clikt subcommand tree. Build it with `./gradlew :samples:cli:installDist` and run `samples/cli/build/install/cli/bin/cli`. [`samples/cli/README.md`](../samples/cli/README.md) is the full catalog; the main subcommands are:

| Subcommand | Purpose |
|---|---|
| `scan ble \| serial \| tcp` | Find Bluetooth advertisements, list serial ports, or check that a TCP host is reachable. |
| `info \| nodes \| packets \| events \| health` | Connect with `--transport=…` and inspect or stream. |
| `send text` | Send a text message and wait for the terminal ack state. |
| `probe ble \| tcp \| serial \| all` | Reconnect loops with per-phase timing; `probe all` aggregates across transports. |
| `tui` | Interactive Mosaic dashboard (connection state, nodes, activity, probe timings). |
| `conformance` | The pre-release scenarios from [`manual-tests.md`](./manual-tests.md), written as a markdown transcript. |

Every session-opening subcommand takes the same `--transport=ble:NEEDLE \| tcp:HOST[:PORT] \| serial:PORT[:BAUD]` spec, parsed by `TransportTarget.parse` from `:core`. A root-level `--json` switches every subcommand to NDJSON envelopes ([ADR-013](./decisions/013-proto-json-envelope.md)).

The CLI uses `SqlDelightStorageProvider(baseDir = "")`, an in-memory database, so each run starts with empty storage. `conformance` stores to a fresh temporary directory on each run.

Expected results:

- `cli tui --transport=tcp:…` renders against a real node.
- `cli probe ble <needle> --runs 5` completes without a `Stage1Draining` timeout once the node is bonded.
- Every subcommand exits non-zero on a `MeshtasticException` and, under `--json`, emits a matching `error` and `done` envelope.

Bluetooth first-connect on macOS raises an OS pairing dialog. The transport publishes `TransportState.Bonding` and does an encrypted warmup read inside `connect()`, so the dialog appears before the engine's handshake clock starts; `cli probe ble` exercises that path.

## `parity-app`

A Compose Multiplatform sample with one shared UI on Android, iOS and JVM desktop, driving the SDK over TCP (PhoneAPI port 4403).

| Target | Build or run |
|---|---|
| Android | `./gradlew :samples:parity-android-app:assembleDebug` |
| iOS Simulator | `./gradlew :samples:parity-app:linkReleaseFrameworkIosSimulatorArm64`, then open `samples/parity-app/iosApp/iosApp.xcodeproj` in Xcode |
| JVM desktop | `./gradlew :samples:parity-app:run` |

The sample is a smoke test for the cross-platform surface, not a transport showcase. TCP avoids Android Bluetooth permissions, iOS Bluetooth entitlements and the pairing dialog, so it runs from a laptop on the same Wi-Fi as a node. The CLI's `probe ble` covers Bluetooth.

`samples/parity-app` builds a `ComposeApp.framework` for `iosArm64` and `iosSimulatorArm64` (Compose Multiplatform publishes no `iosX64`). `iosApp.xcodeproj` consumes it through the standard `embedAndSignAppleFrameworkForXcode` run-script phase. The `meshtastic.ios.framework` convention plugin applies SKIE to `:core`, `:transport-tcp`, `:transport-ble`, `:transport-websocket` and `:storage-sqldelight`, so sealed types, `Flow`s and suspend functions bridge to Swift `enum`, `AsyncSequence` and `async throws`. The SDK publishes no `RadioClient` XCFramework for SwiftPM ([ADR-007](./decisions/007-ios-distribution.md)); the sample shows the KMP-to-Swift surface works end to end.

CI checks that:

- The Android APK assembles with `:samples:parity-android-app:assembleDebug`.
- The iOS framework links with `:samples:parity-app:linkReleaseFrameworkIosSimulatorArm64`.
- `xcodebuild build` on `iosApp.xcodeproj` succeeds. The SwiftUI shell calls `MainViewControllerKt.MainViewController()`, which proves SKIE generated the bridged surface ([`SwiftIntegrationTests/README.md`](../samples/parity-app/iosApp/SwiftIntegrationTests/README.md)).

`:samples:parity-app:run` opens the desktop window.

## `parity-android-app`

The Android application module. It exists because AGP 9 rejects `com.android.application` alongside the KMP plugin in one module. It applies only `com.android.application` and `composeCompiler`, and depends on `:samples:parity-app` (the KMP library with the shared UI) and `:storage-sqldelight` (for `AndroidContextHolder`). Build it with `./gradlew :samples:parity-android-app:assembleDebug`.

## Shared rules

- Sample-only dependencies (Mosaic, Clikt, Compose) stay in the samples and never reach the library modules.
- Samples depend on the SDK modules as Gradle project dependencies, so they always build against the current source.

## Related

- [ADR-006](./decisions/006-multi-module-rationale.md): the module split that gives samples their own slot.
- [`api-reference.md`](./api-reference.md): the surface the samples consume.
- [`manual-tests.md`](./manual-tests.md): manual conformance scenarios.
