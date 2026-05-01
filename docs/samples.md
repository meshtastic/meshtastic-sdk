# Sample apps

> Reference consumers of `meshtastic-sdk`. **Not published.** Live under `samples/` and serve as the proving ground for the SDK from a host's perspective.

Per [ADR-006](./decisions/006-multi-module-rationale.md) the samples depend on whatever the SDK exposes; the SDK does not depend on them.

## Inventory

| Sample | Target | Demonstrates | Phase |
|---|---|---|---|
| [`samples/cli`](#cli) | JVM (TUI + headless) | All three transports (BLE / TCP / serial); full handshake; Mosaic-rendered live dashboard; headless reconnect probes for regression testing | 2–3 |
| [`samples/parity-app`](#parity-app) | Android + iOS + JVM Desktop | One shared Compose Multiplatform UI consuming the SDK over TCP — proves the public surface compiles + renders identically across all three host targets. SKIE bridges the iOS framework. | 5 |
| [`samples/parity-android-app`](#parity-android-app) | Android | Standalone Android application shell that depends on `parity-app` as a library. Splits the Android APK concern from the KMP shared-UI module. | 6 |

## `cli`

A single Kotlin/JVM binary with a Clikt-powered subcommand tree. Build once with `./gradlew :samples:cli:installDist`, then invoke subcommands through `samples/cli/build/install/cli/bin/cli`. See [`samples/cli/README.md`](../samples/cli/README.md) for the full catalogue; the headline subcommands are:

| Subcommand | Purpose |
|---|---|
| `scan ble \| serial \| tcp` | Discover candidate devices on one transport family. |
| `info \| nodes \| packets \| events \| health` | Connect via `--transport=…` and inspect or stream. |
| `send text` | Transmit a text message and await the terminal ack state. |
| `probe ble \| tcp \| serial \| all` | Reconnect loops with per-phase timing; `probe all` aggregates across transports. |
| `tui` | Interactive Mosaic dashboard (connection state, nodes, activity, probe timings). |

Every session-opening subcommand takes the same `--transport=ble:NEEDLE \| tcp:HOST[:PORT] \| serial:PORT[:BAUD]` spec. Pass `--json` at root level to switch every subcommand to line-delimited NDJSON envelopes (contract in the sample README).

### Storage

`:storage-sqldelight` writing to a path supplied by the caller via `SqlDelightStorageProvider(baseDir = …)`. The CLI today passes an empty `baseDir` (process working directory); a future change will resolve to `~/.config/radio-client/<identity>/db.sqlite` per XDG conventions.

### Acceptance

- `cli tui --transport=tcp:…` renders against a real radio.
- `cli probe ble <needle> --runs 5` completes without a `Stage1Draining` timeout (post-bond).
- Every subcommand exits non-zero on any `MeshtasticException` and emits a matching `error` / `done` envelope under `--json`.

### Notes

- BLE first-connect on macOS triggers an OS pairing dialog. The SDK now emits `TransportState.Bonding` and does an inline encrypted warmup-read inside `transport.connect()` so the dialog appears before the engine handshake clock starts. `cli probe ble` exercises this path.

## `parity-app`

Compose Multiplatform sample with one shared UI on Android, iOS, and JVM
desktop. Drives the SDK over TCP (PhoneAPI port 4403). Sprint 6 deliverable
proving the SDK is consumable from all three platforms with the same code.

| Target | Build / run command |
|---|---|
| Android | `./gradlew :samples:parity-android-app:assembleDebug` (APK) |
| iOS Simulator | `./gradlew :samples:parity-app:linkReleaseFrameworkIosSimulatorArm64` then open `samples/parity-app/iosApp/iosApp.xcodeproj` in Xcode |
| JVM Desktop | `./gradlew :samples:parity-app:run` |

**Why TCP only?** The parity sample is a smoke test for the SDK's
cross-platform surface, not a full transport showcase. TCP avoids Android BLE
permissions, iOS Bluetooth entitlements, and the OS pairing dialog — making
the sample trivial to demo from a laptop on the same WiFi as a device. BLE is
covered by the `cli probe ble` headless path.

**iOS framework wiring.** `samples/parity-app` produces a `ComposeApp.framework`
consumed by `samples/parity-app/iosApp/iosApp.xcodeproj` via the standard
`embedAndSignAppleFrameworkForXcode` Run Script phase. SKIE is applied across
the SDK's KMP library modules (`:core`, `:transport-tcp`, `:transport-ble`,
`:storage-sqldelight`) via the `meshtastic.ios.framework` convention plugin
so Kotlin sealed types, `Flow`s, and suspend functions bridge to Swift `enum`,
`AsyncSequence`, and `async throws`.

The dedicated `RadioClient.xcframework` for SPM consumers (per ADR-007) lands
in a follow-up sprint together with KMMBridge automation; today's sample
proves the underlying KMP-to-Swift surface works end-to-end.

### Acceptance

- Android APK assembles via `:samples:parity-android-app:assembleDebug`.
- iOS framework assembles via `:samples:parity-app:linkReleaseFrameworkIosSimulatorArm64`.
- `xcodebuild build` on `iosApp.xcodeproj` succeeds — the SwiftUI shell calls
  `MainViewControllerKt.MainViewController()`, which proves SKIE generates the
  bridged Swift surface correctly.
- JVM Desktop launches an interactive window via `:samples:parity-app:run`.

## `parity-android-app`

Standalone Android application module. Exists because AGP 9 rejects
`com.android.application` applied alongside the KMP plugin in the same module.
This module applies only `com.android.application` + `composeCompiler` and
depends on `:samples:parity-app` (the KMP library with shared Compose UI) plus
`:storage-sqldelight` (for `AndroidContextHolder`).

| Build command |
|---|
| `./gradlew :samples:parity-android-app:assembleDebug` |

## Cross-cutting expectations

- Sample-specific dependencies (Mosaic, Kable JVM) are scoped to the sample and never leak to the library modules.
- Samples track the SDK's currently-tagged version via `gradle/libs.versions.toml`; they are not published.

## Related

- [ADR-006](./decisions/006-multi-module-rationale.md) — module split puts samples in their own slot
- [`api-reference.md`](./api-reference.md) — the surface every sample consumes
- [`manual-tests.md`](./manual-tests.md) — manual conformance scenarios
