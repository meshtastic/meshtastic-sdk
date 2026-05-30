# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Changed

- **build:** Aligned the toolchain with Meshtastic-Android — Kotlin 2.3.21 (SKIE 0.10.12), Wire 6.4.0, Ktor 3.5.0, and stable coroutines 1.11.0 (was 1.11.0-rc02). Validated locally: iOS framework link, jvmTest, and Kotlin ABI check all green.
- **docs(SPEC.md):** Bumped spec from v2.1 to v2.2 — full post-audit sync aligning spec with shipped implementation. Key areas synchronized: AdminApi expansion (~15 → ~45 methods), `StoreForwardApi`, presence tracking (`WentOffline`/`CameOnline`), `AutoReconnectConfig`, `CongestionWarning`/`ExternalConfigChange`/`StorageDegraded` MeshEvent variants, send DSL, `connectAndAwaitReady()`, `SessionPasskey`, `ConfigBundle.deviceUIConfig`, `SendFailure.IdCollision`/`AckTimeout`/`HandshakeFailed`, `AdminResult` extensions, `ConnectionState` extensions, `MeshtasticException` context fields, convention plugin + version catalog correction (JVM 17→21, Android SDK→36, Kotlin 2.3.20).
- **docs:** Synchronized `api-reference.md`, `error-taxonomy.md`, `roadmap.md`, `module-graph.md`, `README.md`, `CONTRIBUTING.md` with spec v2.2 changes.

## [0.1.0] — 2026-05-01

Initial release of the Meshtastic Kotlin Multiplatform SDK.

### Highlights

- **Full PhoneAPI handshake** — two-stage config exchange, session passkey seeding, auto time-sync.
- **Three transports** — BLE (Kable), TCP (Ktor sockets), USB-serial (jSerialComm / usb-serial-for-android).
- **Persistent storage** — SQLDelight-backed NodeDB, channels, configs with WAL + transactional writes.
- **Engine actor model** — single-writer concurrency (ADR-002), structured coroutine topology.
- **ACK correlation & retries** — `MessageHandle` tracks `Queued → Sent → Acked/Delivered/Failed` with configurable timeout.
- **Phase 2 RPC surfaces** — `AdminApi` (16 methods + `editSettings` transaction), `TelemetryApi`, `RoutingApi`.
- **Ergonomics helpers** — `NodeId.toHex()`, `ChannelUrl` codec, `BatteryStatus`, `RadioMetrics`, typed payload accessors, `connectAndAwaitReady()`.
- **Auto-reconnect supervisor** (opt-in) — exponential backoff with jitter, `ConnectionState.Reconnecting`.
- **Kotlin ABI baselines** — `checkKotlinAbi` gate on every PR.
- **Architecture enforcement** — detekt `ForbiddenImport` + `:core:verifyModuleBoundary`.
- **CI** — SHA-pinned actions, JVM matrix, iOS sim tests, CodeQL, Scorecard, dependency review.
- **CLI sample** — Mosaic TUI dashboard exercising all three transports.

### Targets

`jvm`, `androidTarget` (minSdk 26), `iosArm64`, `iosX64`, `iosSimulatorArm64`.
