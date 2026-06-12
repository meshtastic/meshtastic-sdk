# ADR 006 — Multi-module rationale

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads
**Supersedes:** none
**Related:** [`../SPEC.md`](../SPEC.md) §2, ADR-000 (charter), ADR-001 (proto types), ADR-002 (architecture), ADR-007 (iOS distribution), [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md), [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client) (single-module precedent)

---

## Context

`mqtt-client` is a single Gradle module — and that's fine for a single-protocol library with no platform-specific transport variants. `meshtastic-sdk` is different:

- Multiple **transports** (BLE, TCP, USB-serial, JVM-serial today; HTTP, MQTT-proxy, RPC on the post-1.0 roadmap) with different platform-availability matrices and disjoint heavyweight dependencies (Kable, usb-serial-for-android, jSerialComm).
- A pluggable **storage** layer (SQLDelight today; in-memory in `:testing` for tests).
- Sample apps (CLI, Android, iOS, desktop) that are not part of the published library.

A monolithic module would force every consumer to compile-time-depend on Android-only and JVM-only code (Kable + jSerialComm + SQLDelight + …) even when they target only `iosArm64`. Worse, it would compile-couple `:core` (the engine) to every transport, killing our ability to enforce "engine doesn't depend on transports" via the Gradle graph.

## Decision

### MVP module layout

```
meshtastic-sdk/
├── build-logic/convention/        ── precompiled script plugins (KMP target setup, publishing, Dokka, lint)
│
├── proto/                         ── Wire-generated DTOs from the protobufs submodule (PUBLIC per ADR-001)
│                                     targets: android, jvm, ios{Arm64,X64,SimulatorArm64}
├── core/                          ── public API: RadioClient, engine, sealed events,
│                                     RadioTransport interface + Frame + TransportIdentity,
│                                     StorageProvider / DeviceStorage interfaces
│                                     targets: android, jvm, ios{Arm64,X64,SimulatorArm64}
│                                     api(project(":proto"))   so org.meshtastic.proto.* is transitive
│
├── transport-ble/                 ── Kable                              targets: android, jvm, ios
├── transport-tcp/                 ── Ktor sockets                       targets: android, jvm, ios
├── transport-serial/              ── usb-serial-for-android (android) +
│                                     jSerialComm (jvm), unified via expect/actual
│                                     targets: android, jvm
│
├── storage-sqldelight/            ── SQLDelight                         targets: android, jvm, ios
│
├── bom/                           ── Maven BOM (publishes <dependencyManagement>)
│
├── testing/                       ── InMemoryStorage, FakeRadioTransport, test utilities
│                                     targets: android, jvm, ios
│
└── samples/
    ├── cli/                       ── jvm
    ├── android-app/               ── android
    ├── ios-app/                   ── iosArm64 + simulator (consumed via KMMBridge per ADR-007)
    └── desktop/                   ── jvm (Compose Multiplatform)
```

> **There is NO `:transport-api` module.** The `RadioTransport` interface, `Frame`, and `TransportIdentity` live in `:core` (per SPEC §3.4). Splitting them out would create a tiny artifact every transport must depend on, with no testability or layering benefit — `:core` already exposes the interface as part of its public surface.

### Roadmap modules (post-1.0; not in MVP)

These are designed but deferred. See [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md):

```
transport-http/                   ── Ktor client; targets: android, jvm, ios, wasmJs
transport-mqtt-proxy/             ── meshtastic/mqtt-client (proxy mode); side-channel, NOT a RadioTransport
transport-rpc/                    ── Ktor WS client; remote-engine adapter
rpc/                              ── snapshot+delta envelopes, versioned wire format
host-rpc-server/                  ── JVM RPC server hosting an engine for wasm clients
storage-okio-files/               ── Okio file IO storage
samples/wasm-app/                 ── wasmJs (uses :transport-rpc against a host-rpc-server)
samples/host-rpc-server/          ── reference jvm RPC host
```

When the roadmap lands, `:core` adds a `wasmJs` source set containing only RPC-compatible code — no native transports, no SQLDelight on wasm.

### Dependency rules (enforced by Gradle + `:core:verifyModuleBoundary`)

1. **`:core` depends on `:proto`. Nothing else.** No transport implementation, no storage implementation. The `RadioTransport`, `StorageProvider`, and `DeviceStorage` interfaces are declared inside `:core` itself.
2. **Transport modules depend on `:core` (for the `RadioTransport` interface) and `:proto`.** They expose a single factory function (e.g., `BleTransport(spec)`) returning `RadioTransport`. The consumer wires it at `Builder.transport(...)`.
3. **Storage modules depend on `:core` (for the `StorageProvider` / `DeviceStorage` interfaces) and `:proto`.** Same wiring pattern as transports.
4. **`:bom` depends on every published artifact** for the sole purpose of publishing a `<dependencyManagement>` BOM POM.
5. **`:samples/*` and `:testing` may depend on anything.**
6. **No module under `samples/` is published.** Their `maven-publish` is disabled.

### Per-target subset (MVP)

| Module | android | jvm | iosArm64 | iosX64 | iosSimulatorArm64 |
|---|:-:|:-:|:-:|:-:|:-:|
| `:proto` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:core` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:transport-ble` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:transport-tcp` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:transport-serial` | ✅ | ✅ | — | — | — |
| `:storage-sqldelight` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:testing` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:bom` | n/a (Maven BOM) |

### `wasmJs` posture (post-1.0)

`wasmJs` is **not** an MVP target on any module. When the wasm/RPC roadmap lands:

- `:core` and `:proto` gain a `wasmJs` source set.
- Only `:transport-rpc` (and optionally `:transport-http`, `:transport-mqtt-proxy`) carry `wasmJs` source sets.
- `:transport-ble`, `:transport-tcp`, `:transport-serial-*`, and `:storage-sqldelight` will not target `wasmJs`.

Implication for MVP design: even though `wasmJs` is deferred, `:core`'s `commonMain` should avoid APIs that lack a `wasmJs` implementation in `kotlinx-coroutines` / `kotlinx-datetime` / Okio, so the future addition is non-breaking. We pin compatible versions in `libs.versions.toml`.

### Versioning

All modules ship with the same version (`axion-release` git-tag-driven). A release publishes every module; consumers pick which they need. The artifact prefix is `sdk-` (e.g., `org.meshtastic:sdk-core`, `org.meshtastic:sdk-transport-ble`, `org.meshtastic:sdk-bom`).

## Alternatives considered

- **Single module, like `mqtt-client`.** Rejected — drags every transport's platform dependencies into every consumer's classpath, and we can't enforce "engine doesn't depend on transports" via Gradle if everything is in one module. (Discussed at length in SPEC §2.)
- **Separate `:transport-api` module.** Rejected — see callout above. Saves nothing; costs an extra publish coordinate.
- **Split engine into `:engine` and `:radio` (public).** Rejected — adds indirection without testability win. The engine is `internal` inside `:core`; tests in `:core/src/commonTest` reach it via `internal` visibility.
- **Per-platform transport modules (`:transport-ble-android`, `:transport-ble-ios`).** Rejected — Kable IS the cross-platform abstraction; splitting would re-introduce per-platform divergence Kable already solves. Same logic for TCP (Ktor).
- **`:proto-raw` escape-hatch artifact.** Rejected per ADR-001 — proto types are the public model, no escape hatch needed.
- **Combine `:storage-*` into `:core`.** Rejected — would force every consumer to take a SQLDelight dependency they may not want (e.g., embedded environments using a hand-rolled `StorageProvider`).
- **Promote roadmap modules into MVP.** Rejected — wasm/RPC scope is deferred per [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md); MQTT-proxy lands in Phase 6 once the engine is stable.
- **Room (Android Jetpack) instead of SQLDelight.** Rejected — SQLDelight is designed for KMP from the ground up; Room is Android-first and doesn't ship a mature JVM/iOS backend. **Key differences:** SQLDelight generates explicit SQL queries (type-safe, compile-checked) with no runtime reflection; Room relies on annotation processing and LiveData binding (simpler for Android-only projects, but heavier and less portable). For a KMP project targeting JVM, Android, and iOS, SQLDelight's first-class multiplatform support and explicit query model was the clear choice.

## Consequences

- **Adding a new transport is local.** A new module under `transport-*`, depending on `:core` (for the interface) + `:proto`. Zero changes to `:core`.
- **Adding a new storage backend is local.** A new module under `storage-*`, depending on `:core` (for the interfaces) + `:proto`.
- **Bumping the proto submodule re-publishes everything.** Acceptable — it's the protocol bump that justifies the release.
- **Per-module `updateKotlinAbi` / Dokka.** Each module has its own `api/` directory; CI checks all of them (`:proto` is exempt per `versioning.md`). Dokka publishes a unified site with a left-nav per module.
- **The Gradle build graph is the architecture.** The `:core:verifyModuleBoundary` task enforces in code what the Gradle dependency graph already enforces at compile time, so violations surface in two places.
- **Future wasm pays nothing for native transports.** When the wasm source sets land, they exclude platform-only modules cleanly because each module already declares its targets independently.
