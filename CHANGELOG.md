# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- **events:** Inbound MQTT client-proxy, XModem, and file-info frames are now surfaced as typed events — `MeshEvent.MqttProxyMessage`, `MeshEvent.XmodemPacket`, `MeshEvent.FileInfo` — instead of being dropped with a `ProtocolWarning`. Outbound counterparts go through `RadioClient.sendRaw(ToRadio(...))`.
- **send:** `SendFailure.QueueRejected(res)` — a firmware `QueueStatus.res != 0` (transmit queue full/rejected) now fast-fails the `MessageHandle` (and any pending admin RPC sharing the wire id) instead of waiting out the full ACK timeout.
- **send:** `RadioClient.sendText(..., replyId)` for threaded replies (`decoded.reply_id` without the emoji flag).
- **engine:** Mesh packets received while the handshake is still in flight (live traffic interleaved with the config/NodeDB drain, including the seeding window) are now buffered (drop-oldest at 64, observable via `PacketsDropped`) and flushed through the normal packet pipeline at Ready — previously they were silently dropped.

### Fixed

- **engine:** A `want_config_id` retry restarts the firmware's config drain from scratch (PhoneAPI resets its read index), which previously duplicated channels / config sections in the committed `ConfigBundle` and `channels` state. Stage 1 accumulators now replace by key (channel index / config section), latest occurrence wins.
- **engine:** `FromRadio.lockdown_status` (protobufs 2.7.25+) was silently dropped — it now surfaces as a structured `ProtocolWarning` until typed lockdown support lands.

### Removed

- **`RadioClient.close()` / `AutoCloseable` (breaking):** the blocking `close()` bridge
  (`runBlocking { disconnect() }`) was an ANR/deadlock trap on Android and iOS main threads.
  Lifecycle is suspend-only — use `try { … } finally { client.disconnect() }`.

### Changed (API conventions, breaking)

- **One byte-string vocabulary.** `Frame`, `SessionPasskey`, and the streaming `send` overload
  now use `okio.ByteString` — the type Wire-generated proto fields already force into the
  surface — instead of `kotlinx.io.bytestring.ByteString`. The `send(portnum, payload:
  kotlinx.io.Buffer)` overload is replaced by `send(portnum, payload: okio.ByteString)`.
  `kotlinx-io` is no longer a dependency of any published module.
- `docs/api-reference.md` gains an **API conventions** section codifying the proto-exposure,
  byte-string, no-blocking-bridge, data-class, and Kotlin/Swift-first policies (Poko migration
  for event/result types tracked in the roadmap pending Kotlin 2.3.21 support).

### Changed

- **remote admin (breaking behavior, correctness):** Remote admin packets are now routed the way modern firmware (2.5+) requires — `pki_encrypted = true` + the target's `public_key` on channel 0 when both nodes have published keys, falling back to a channel named `admin` otherwise, with priority `RELIABLE`. Previously remote admin went out on channel 0 in the clear and was rejected by current firmware.
- **remote admin:** Session passkeys are now cached **per node** (each node issues its own in its admin responses; every inbound admin response refreshes the issuer's entry). Previously a single shared slot cross-contaminated concurrent admin sessions against different nodes and stamped the *local* node's passkey onto remote targets. The `SessionKeyExpired` single-shot retry now re-seeds against the *target* node.
- **storage:** Documented that `DeviceStorage.loadNodes()` is never called by the engine (node DB reseeds from the handshake); it exists for hosts (offline node access) and tests.
- **transport-ble (Android):** `BleTransport(address)` now negotiates the ATT MTU (517) after each link establishment and requests `CONNECTION_PRIORITY_HIGH` for the 30-second handshake window before downgrading to Balanced. Without the MTU request Android stays at the BLE minimum (23) and any ToRadio write over 20 bytes fails.
- **build:** Kable 0.42.0 → 0.43.0 (aligns with Meshtastic-Android).
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
