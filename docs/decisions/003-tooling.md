# ADR 003 — Tooling stack

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads
**Supersedes:** none
**Related:** [`../SPEC.md`](../SPEC.md) §5, ADR-000 (charter), ADR-001 (proto choice), [`../versioning.md`](../versioning.md) (SemVer, ABI, release policy — authoritative source), [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client) (sibling KMP library)

---

## Context

`meshtastic-sdk` is a Kotlin Multiplatform library targeting Android, JVM, iOS (`Arm64`/`X64`/`SimulatorArm64`) for MVP, with `wasmJs` (RPC client only) on the post-1.0 roadmap once the supporting tooling matures (see [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md)). It needs:

- A protobuf codec that produces Kotlin types directly consumable in `commonMain`.
- A BLE client that works on both Android and iOS (`commonMain` callers).
- A TCP/HTTP client that works on every JVM/native target.
- Serial port support on Android (USB) and JVM.
- Storage abstraction with at least one production-grade implementation.
- Test, build, and release tooling that matches the rest of the org.

The org already publishes a sibling KMP library — [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client) — which establishes a working stack. Where that stack fits, we adopt it unchanged.

## Decision

The locked stack, recorded in `gradle/libs.versions.toml` from Phase 0:

### Build & language

| Tool | Version policy | Notes |
|---|---|---|
| **Kotlin** | Latest stable at repo creation; bumped at start of each phase | Multiplatform default. K2 compiler. |
| **Gradle** | 8.x (latest stable) | Wrapper committed. JDK 21 toolchain. |
| **JDK toolchain** | 21 (`org.gradle.java.installations.fromEnv` accepted) | JVM target bytecode: 17 |
| **Android Gradle Plugin** | latest stable | Android compileSdk = latest stable; minSdk = 26 |
| **Convention plugins** | `build-logic/convention/` (precompiled script plugins) | Single source of truth for KMP target setup, publishing, Dokka, lint config |

### Codegen & wire format

| Tool | Why |
|---|---|
| **Square Wire 6.x** | Generates idiomatic Kotlin protobuf classes that survive multiplatform (no JVM-only ProtoBuf descriptors). Maps `oneof` to sealed classes, exposes builders, supports proto3 + edition 2023. ADR-001 mandates these as the public data model. |
| **`com.squareup.wire:wire-gradle-plugin`** | Drives generation from the `proto/src/protobufs` git submodule into `commonMain` of the `:proto` module. |

We considered `protobuf-kotlin` (Google's Java/Kotlin codegen) and rejected it for Android+JVM bias and a less Kotlin-idiomatic API.

### IO, time, and concurrency primitives

| Tool | Where | Why |
|---|---|---|
| `kotlinx-coroutines` | everywhere | Required by KMP idiom; underpins the actor model (ADR-002). |
| `kotlinx-coroutines.sync.Mutex` | rare, guarded | Allowed only outside the engine package. |
| `kotlinx-datetime` | everywhere | `Instant`, `Duration`, `Clock.System`. **No `java.time.*` in `commonMain`.** |
| `kotlinx.io.bytestring.ByteString` | public API + commonMain payloads | Matches `mqtt-client` house style. **`Frame.bytes` and any public byte-payload field is `kotlinx.io.bytestring.ByteString`.** |
| `okio.ByteString` / `okio.Buffer` | transport-internal IO only | Kept for Ktor socket interop where it's idiomatic. Never crosses the public API. |
| `kotlinx.atomicfu` | engine-internal counters (request_id, heartbeat nonce) | Single-writer guarantee documented at each call site. |

> **Superseded (0.2.0):** The byte-string rows above were reversed in 0.2.0 — `okio.ByteString` is the **single** public byte vocabulary, because Wire's generated protos already expose it on every proto-typed call; `kotlinx-io` was removed entirely and is no longer a dependency of any module. `Frame.bytes` and every public byte-payload field is `okio.ByteString`.

We do not allow `java.util.concurrent`, `java.util.Date`, or `android.os.*` in `commonMain` — this is enforced by detekt's `ForbiddenImport` rules in `config/detekt/detekt.yml` (see ADR-008).

### Transports (MVP)

| Module | Library | Targets | Why |
|---|---|---|---|
| `transport-ble` | **Kable** | android, jvm, ios | Established KMP BLE library, also used as the BLE backend in `Meshtastic-Android`. Ships native backends for Android, iOS, and JVM (macOS, Windows, Linux). |
| `transport-tcp` | **Ktor sockets** (`io.ktor:ktor-network`) | android, jvm, ios | Lowest-level Ktor primitive; no HTTP overhead. |
| `transport-serial` | **usb-serial-for-android** (android) + **jSerialComm** (jvm), unified via `expect/actual` | android, jvm | USB OTG on Android (app handles `UsbManager.requestPermission(...)`); jSerialComm on JVM. Single artifact per ADR-006. |

### Transports (future / post-1.0 — not in MVP)

These are designed-but-deferred; the table is informational so the MVP decisions above are not re-litigated when the roadmap modules land. See [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md) for the wasm/RPC subset.

| Module | Library | Targets | Why deferred |
|---|---|---|---|
| `transport-http` | Ktor client | android, jvm, ios, wasmJs | PhoneAPI HTTP polling endpoints (`protocol.md` §4); demand follows wasm |
| `transport-mqtt-proxy` | `org.meshtastic:mqtt-client` | all | *device-as-MQTT-proxy* mode (`protocol.md` §14); side-channel, NOT a `RadioTransport`. Phase 6. |
| `transport-rpc` | Ktor client (WS) | all incl. wasmJs | The wasm client path: connects to a JVM `host-rpc-server` that owns the radio. |

### Storage

| Module | Library | Targets |
|---|---|---|
| `storage-sqldelight` | **SQLDelight** | android, jvm, ios |
| `:testing` | in-process `InMemoryStorage` | all |

> A future `storage-okio-files` module is sketched in the roadmap but not part of MVP — SQLDelight covers every supported target today.

Storage is **required** at `Builder.build()` time — no in-memory default in `:core`. Hosts that don't want SQLDelight can roll their own `StorageProvider`.

### Test stack

| Tool | Purpose |
|---|---|
| **Kotlin Test** | Common assertions across targets. |
| **Kotest** (`kotest-property`, `kotest-assertions-core`) | Property-based tests for `WireCodec`, `MessageQueue`, `HandshakeMachine`. |
| **Turbine** | `Flow` assertions in coroutine tests. |
| **MockK** (jvm/android) | Last-resort mocking. We prefer real fakes (`InMemoryStorage`, `FakeRadioTransport`) per ADR-002. |
| **detekt `ForbiddenImport` + `:core:verifyModuleBoundary`** | Architecture rules: detekt bans `java.*`/`android.*` in `commonMain` and hints against `kotlin.Result<T>` in public API; the Gradle `:core:verifyModuleBoundary` task enforces that `:core` does not depend on transport modules (see ADR-008). |
| **`binary-compatibility-validator`** (`updateKotlinAbi`) | API surface freezes. From Phase 5 every public symbol change MUST regenerate `api/` files in the same commit. |
| **Dokka 2.x** | API docs. Coverage gate from Phase 5. |
| **Compose previews / sample apps** | Manual smoke (`samples/cli`, `samples/parity-app`, `samples/parity-android-app`). |

### Lint & format

| Tool | Notes |
|---|---|
| **Detekt** | Default ruleset + a small custom rule pack (no `Result<T>` in public, no `java.util.Date`, etc.). |
| **ktfmt (Kotlinlang style)** | Single style across the org. Pre-commit and CI gate. |
| **EditorConfig** | committed `.editorconfig`. |

### Release

| Tool | Notes |
|---|---|
| **`pl.allegro.tech.build.axion-release`** | Git-tag-driven SemVer. Releases via `./gradlew release`. |
| **Vanniktech `gradle-maven-publish-plugin`** | Publishes direct to **Sonatype Central Portal** (`publishAndReleaseToMavenCentral`); GPG-signed via in-memory key. Replaces the OSSRH staging-repo dance — no separate `gradle-nexus-publish-plugin` and no `closeAndReleaseSonatypeStagingRepository` step. Same plugin used by sibling `mqtt-client`. See [`../versioning.md`](../versioning.md) and [`../ci-cd.md`](../ci-cd.md) for credentials and workflow. |
| **KMMBridge** | iOS XCFramework + sibling SPM repo (`meshtastic/meshtastic-sdk-spm`); see [ADR-007](./007-ios-distribution.md). |

### CI

| Stage | Tool |
|---|---|
| PR build (lint, test, checkKotlinAbi, detekt + `:core:verifyModuleBoundary`) | GitHub Actions matrix (linux for jvm/android, macos for ios) |
| Release publish | Manual workflow dispatch on signed tag |
| Dokka publish | GitHub Pages |
| Sample-app smoke | Headless emulator (Android), Xcode build only (iOS) |

## Alternatives considered

- **`protobuf-kotlin` instead of Wire.** Rejected per ADR-001 + worse multiplatform story.
- **`kotlinx.serialization` for protobuf.** Rejected — the `kotlinx-serialization-protobuf` runtime decodes from a manual `@Serializable` schema; we lose the `meshtastic/protobufs` schema as the source of truth.
- **Okio for public payloads.** Rejected — diverges from `mqtt-client` and the broader `kotlinx-io` direction. Kept for transport-internal use.
  > **Superseded (0.2.0):** This rejection was itself reversed — `okio.ByteString` is now the public payload type (Wire's generated protos force it into the surface anyway), and `kotlinx-io` was removed entirely. See the note in "IO, time, and concurrency primitives" above.
- **Kermit for logging.** Considered. We instead expose a `LogSink` interface and ship `Kermit` only as a sample binding under `:samples`. Production hosts likely already have a logger; we won't dictate.
- **Koin for DI.** Rejected — SDK does not use DI internally; consumers wire whatever they want.
- **JReleaser.** Considered for release; `axion-release` matches the org's other libs, prefer consistency.

## Consequences

- **One stack across the org.** A contributor moving between `mqtt-client` and `meshtastic-sdk` finds the same Kotlin version, same Gradle conventions, same test runner, same publishing flow.
- **Wire 6 lock.** Bumping major Wire is a breaking change for proto consumers; treated as a SemVer-major event.
- **No `java.*` in `commonMain` is enforced, not aspirational.** Detekt's `ForbiddenImport` rule fails the build if a `java.util.UUID` import sneaks in.
- **Two ByteString types in the codebase** (kotlinx-io public, Okio internal). Reviewers must keep public surface kotlinx-io. KGP `checkKotlinAbi` plus detekt's `ForbiddenImport` covers the `:core` public symbols.
  > **Superseded (0.2.0):** There is now **one** ByteString type — `okio.ByteString` everywhere, public and internal; `kotlinx-io` was removed entirely. The detekt `ForbiddenImport` rules now ban `kotlinx.io.*` imports instead (rule added in 0.2.0).
- **`wasmJs` is post-1.0.** ADR-006 details the MVP per-target matrix and the future wasm posture.
