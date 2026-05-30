# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

Meshtastic-Android integration gaps — closing the remaining feature parity between the SDK and
the Meshtastic-Android command surface.

### Added

- **`RadioClient.requestNodeInfo(node: NodeId)`** — request a remote node's `NodeInfo` (reply surfaces via the `nodes` flow).
- **`RadioClient.requestPosition(from, channel)`** convenience extension — request a node's current `Position`.
- **`RadioClient.sendReaction(emoji, to, channel, replyId)`** — tapback reactions.
- **`RadioClient.sendRaw(frame: ToRadio)`** — raw `ToRadio` escape hatch for MQTT proxy and XModem flows.
- **`RadioClient.textMessages: Flow<MeshPacket>`** plus typed payload accessors (`asText`, `asPosition`, `asNodeInfo`, `asNodeInfoUser`, `asNeighborInfo`).
- **Remote admin** — `AdminApi.forNode(dest: NodeId)` returns an `AdminApi` whose calls route to a remote node.
- **Complete `AdminApi` proto coverage** — final operations, the missing admin time operation, admin batch getters, and `editSettings { … }` config-DSL builders for all `Config`/`ModuleConfig` types.
- **`AdminResult.getOrThrow()`** plus the `AdminResultException` hierarchy and chainable `onSuccess` / `onFailure` inspectors.
- **`StoreForwardApi`** — Store-and-Forward server discovery, history/stats requests, S&F events, and SFPP protocol handling.
- **`MeshTopology`** — incremental, thread-safe mesh graph from `NeighborInfo` reports (shortest path, neighbors, direct-reach queries).
- Engine: congestion emission, presence timer / node-presence tracking, and a retry extension.

### Changed

- **Broadcast sends now set `want_ack = true`** so the engine resolves them to `Acked` on hearing the rebroadcast (implicit-ACK delivery feedback), matching firmware behavior.
- Toolchain aligned with Meshtastic-Android: Kotlin 2.3.21, SKIE 0.10.12, Wire 6.4.0, Ktor 3.5.0, coroutines 1.11.0, AGP 9.2.1.

### Fixed

- Critical engine hardening: thread-safety, request timeout handling, flow caching, and admin/routing error mapping.
- `editSettings` now routes correctly for remote-admin targets.
- SFPP destination normalization in the `SfppLinkProvided` event.

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
