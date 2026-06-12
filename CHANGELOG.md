# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Breaking

Pre-1.0 policy: breaking changes ship in a MINOR bump (0.2.0). Nothing below has ever been
published to Maven Central (0.1.0 was tagged `rc1` only), so these break no external consumer.

- **`RadioClient.close()` / `AutoCloseable` removed** — the blocking bridge
  (`runBlocking { disconnect() }`) was an ANR/deadlock trap on Android and iOS main threads.
  Lifecycle is suspend-only; `withConnection { }` (below) is the structured replacement for
  `use { }`.
- **One byte-string vocabulary.** `Frame`, `SessionPasskey`, and the streaming `send` overload
  now use `okio.ByteString` — the type Wire-generated proto fields already force into the
  surface. `send(portnum, payload: kotlinx.io.Buffer)` is replaced by
  `send(portnum, payload: okio.ByteString)`; `kotlinx-io` is no longer a dependency of any
  published module (and is now detekt-banned via `ForbiddenImport`).
- **`sendText` parameter order is now `(text, to, channel, replyId)`** — aligned with
  `sendReaction(emoji, to, channel, replyId)`. Source-compatible for named/text-only callers;
  binary signature changed (value-class mangling).
- **Duplicate typed-decoder family removed** (`decodeAsText`, `decodeAsPosition`,
  `decodeAsUser`, `decodeAsNodeInfo`, `decodeAsTelemetry`, `decodeAsRouting`, `decodeAsAdmin`)
  — they shadowed the canonical `asText()`/`asPosition()`/… accessors in `PayloadAccessors.kt`.
  The generic `MeshPacket.decodeAs(adapter)` escape hatch remains (no portnum guard — for
  Paxcount/StoreAndForward-style payloads).
- **New sealed variants break exhaustive `when`s:** `MeshEvent.MqttProxyMessage`,
  `MeshEvent.XmodemPacket`, `MeshEvent.FileInfoReceived` (named to avoid shadowing
  `org.meshtastic.proto.FileInfo`), and `SendFailure.QueueRejected(res)`.
- **`nodes` flow seeding model:** every subscription now receives a fresh
  `NodeChange.Snapshot` first (seeded via `onSubscription` from the engine's current node
  map); the engine's SharedFlow no longer keeps a replay slot. Previously a late subscriber
  got whatever **delta** happened to be emitted last instead of the Snapshot, leaving
  `nodeMap()`-style folds near-empty until the next reconnect.
- **Session passkeys are no longer persisted** — they are per-node and in-memory only. The
  local node's passkey is never required (firmware rewrites phone packets to `from = 0`,
  which is passkey-exempt) and remote passkeys expire after ~4 minutes, so persistence bought
  nothing. `DeviceStorage.saveSessionPasskey`/`loadSessionPasskey` remain in the interface as
  host-facing capabilities (like `loadNodes`).

### Added

- **events:** Inbound MQTT client-proxy, XModem, and file-info frames are now surfaced as typed events — `MeshEvent.MqttProxyMessage`, `MeshEvent.XmodemPacket`, `MeshEvent.FileInfoReceived` — instead of being dropped with a `ProtocolWarning`. Outbound counterparts go through `RadioClient.sendRaw(ToRadio(...))`.
- **send:** `SendFailure.QueueRejected(res)` — a firmware `QueueStatus` enqueue rejection now fast-fails the `MessageHandle` (and any pending admin RPC sharing the wire id) instead of waiting out the full ACK timeout.
- **send:** `RadioClient.sendText(..., replyId)` for threaded replies (`decoded.reply_id` without the emoji flag).
- **engine:** Mesh packets received while the handshake is still in flight (live traffic interleaved with the config/NodeDB drain, including the seeding window) are now buffered (drop-oldest at 64, observable via `PacketsDropped`) and flushed through the normal packet pipeline at Ready — previously they were silently dropped.
- **ergonomics:** `RadioClient { … }` builder-lambda factory (sugar over `RadioClient.Builder`; Swift callers keep the builder).
- **ergonomics:** `RadioClient.withConnection(teardownTimeout = 10.seconds) { … }` — connect, run the block, and always disconnect (success, exception, and cancellation; teardown runs under `NonCancellable`, bounded by `teardownTimeout` so a wedged transport cannot pin the caller forever).
- **ergonomics:** `Flow<NodeChange>.asNodeMap()` / `RadioClient.nodeMap()` — fold the node delta stream into a live `Map<NodeId, NodeInfo>` (the accumulator every consumer otherwise hand-writes), ready for `stateIn`.
- **testing:** `FromRadio.toFrame()` — encode a device-side envelope into a wire `Frame` for use with `FakeRadioTransport.injectFrame` (replaces eight per-test-file copies of the framing helper).

### Fixed

- **remote admin / firmware conformance:** `QueueStatus.res` is decoded in the firmware's **ERRNO namespace**, not `Routing.Error`: `35` (`ERRNO_SHOULD_RELEASE`) is success and now counts as `Sent`; ERRNO rejections (`32` queue-full, `33` no interface, `34` radio disabled) fail pending admin RPCs as `NodeUnreachable`; values `1..31` (genuine `Routing.Error` codes such as `DUTY_CYCLE_LIMIT`) map through the normal routing-error taxonomy. Previously `res = 32` was misread as `BAD_REQUEST`, `33` as `NOT_AUTHORIZED`, and the success code `35` produced a false failure.
- **remote admin:** session passkeys are only latched from **response-shaped** admin messages. Previously a remote node administering *us* would have its request — carrying the passkey *we* issued — latched under the remote's key, poisoning our next RPC to it.
- **remote admin:** the fire-and-forget admin path (`enterDfuMode`, `setTimeOnly`) now posts through the engine actor's inbox; it previously prepared the packet on the caller's coroutine, structurally mutating the actor-owned per-node passkey map (data race under ADR-002).
- **remote admin:** the managed-mode client-side gate now applies only to **local** targets. Firmware rejects only local admin on a managed device (`from == 0` branch); remote targets authorize against their own admin keys — managed-fleet deployments administer remote managed nodes from a managed local node.
- **engine:** a `want_config_id` retry restarts the firmware's config drain from scratch, which previously duplicated channels / config sections in the committed `ConfigBundle` and `channels` state. Stage 1 accumulators now replace by key, latest occurrence wins.
- **engine:** `FromRadio.lockdown_status` (protobufs 2.7.25+) was silently dropped — it now surfaces as a structured `ProtocolWarning` until typed lockdown support lands.
- **engine:** the Stage-1 settle replay no longer drops buffered frames that follow a duplicate Stage-1 completion — the unprocessed remainder is re-buffered for the next settle window.
- **engine:** the seeding window no longer silently drops non-packet `FromRadio` variants — `node_info` merges into the node DB and the rest route through the shared auxiliary handler (client notifications, MQTT proxy, …).
- **engine:** caller-supplied wire-id collisions are now rejected with `SendFailure.IdCollision` at the wire-id key in `dispatchSend`; previously the second send silently overwrote the first handle's bookkeeping, stranding it un-completable forever.
- **engine:** the Stage-2 commit's async storage flush now snapshots the accumulated nodes/channels/heartbeats before launching; it previously iterated live actor-owned collections off-actor (CME risk mis-reported as `StorageDegraded`).
- **transport-ble (Android):** the delayed connection-priority downgrade is now scheduled on a per-connect-cycle scope cancelled at `disconnect()` — a stale 30-second timer from session N can no longer fire mid-handshake of session N+1 (the auto-reconnect path) and defeat the priority boost.

### Changed

- **remote admin (breaking behavior, correctness):** Remote admin packets are now routed the way modern firmware (2.5+) requires — `pki_encrypted = true` + the target's `public_key` on channel 0 when both nodes have published keys, falling back to a channel named `admin` otherwise, with priority `RELIABLE`. Previously remote admin went out on channel 0 in the clear and was rejected by current firmware.
- **remote admin:** Session passkeys are now cached **per node** (each node issues its own in its admin responses; every inbound admin response refreshes the issuer's entry). Previously a single shared slot cross-contaminated concurrent admin sessions against different nodes and stamped the *local* node's passkey onto remote targets. The `SessionKeyExpired` single-shot retry now re-seeds against the *target* node.
- **storage:** Documented that `DeviceStorage.loadNodes()` is never called by the engine (node DB reseeds from the handshake); it exists for hosts (offline node access) and tests.
- **transport-ble (Android):** `BleTransport(address)` now negotiates the ATT MTU (517) after each link establishment and requests `CONNECTION_PRIORITY_HIGH` for the 30-second handshake window before downgrading to Balanced. Without the MTU request Android stays at the BLE minimum (23) and any ToRadio write over 20 bytes fails.
- **docs:** `docs/api-reference.md` gains an **API conventions** section (proto exposure, byte vocabulary, no blocking bridges, data-class policy, Kotlin/Swift-first interop); SPEC bumped to v2.3 and synced; CONTRIBUTING/AGENTS house rules flipped to the okio vocabulary with superseded-by notes preserved in ADR-003.
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
