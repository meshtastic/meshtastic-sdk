# Roadmap: deferred work

This page lists features that are declared in the public surface, or referenced in code and docs, but not wired yet. Each is intentional: the surface exists so consumers can pin against the shape while the implementation lands later.

Long-form design for future work lives in [`./future/`](./future/), such as the remote-RPC roadmap. This page tracks short, code-anchored deferrals.

## Status legend

- **Stub**: the symbol is in the public API and calling it throws `NotImplementedError` (`TODO(...)`).
- **No-op**: the symbol accepts input, but the input has no observable effect yet.
- **Missing**: referenced in docs or code comments only, not in the API surface.
- **Partial**: some of the behavior ships; the row says what is left.

No symbol in the main source sets has Stub status.

## Inventory

Tooling deferral: public event and result `data class`es move to [Poko](https://github.com/drewhamilton/Poko) `@Poko` classes (no `copy`/`componentN` ABI lock-in, same `equals`/`hashCode`/`toString`) once Poko supports the pinned Kotlin version (`kotlin` in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml), 2.4.10). Poko 0.23.1 fails with `NoClassDefFoundError: org/jetbrains/kotlin/extensions/ExtensionPointDescriptor` against the 2.3.x compiler and has not been tried against 2.4.x. Until then the data-class policy in [`api-reference.md`](./api-reference.md#api-conventions) applies.

| ID | Item | Surface | Status | Phase | Source |
|---|---|---|---|---|---|
| R-1 | ~~`RadioClient.admin` (admin RPC)~~ | ~~public~~ | **Done**: [`AdminApi`](../core/src/commonMain/kotlin/org/meshtastic/sdk/AdminApi.kt) with the `editSettings` and `batch` transactions; `SessionKeyExpired` triggers one retry. | — | — |
| R-2 | ~~`RadioClient.telemetry`~~ | ~~public~~ | **Done**: [`TelemetryApi`](../core/src/commonMain/kotlin/org/meshtastic/sdk/TelemetryApi.kt), request methods and the `observe(node)` cold flow. | — | — |
| R-3 | ~~`RadioClient.routing`~~ | ~~public~~ | **Done**: [`RoutingApi`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RoutingApi.kt), `traceRoute(dest, hopLimit)` and `requestNeighborInfo(node)`. | — | — |
| R-4 | ~~`Builder.clock(Clock)` injection~~ | ~~public, no-op~~ | **Done**: `Builder.clock` takes a `kotlin.time.Clock`, wired into `AdminApi.setTime(at = null)`, the post-handshake time sync and admin session expiry. | — | — |
| R-5 | ~~`Builder.protocolLogging(level, redactor)`~~ | ~~public, no-op~~ | **Done**: the engine logs every `FromRadio` and `ToRadio` envelope through `PayloadRedactor` at the configured level. | — | [`RadioClient.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt) |
| R-6 | `Builder.autoSyncTimeOnConnect(...)` | public | **Partial**: one fire-and-forget `AdminApi.setTime()` after every handshake. Gating it on observed clock skew is not done. | 2 | [`RadioClient.connect`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt) |
| R-7 | `TransportSpec`-driven transport factory lookup | public | **Partial**: `TransportTarget.parse` reads `ble:`, `tcp:` and `serial:` specs, `bleAdvertisementMatches` matches a BLE needle (including a MAC against a BlueZ object path), and `JvmSerialPorts.findMeshtastic` and `byIdPath` find serial nodes. Opening a transport from a target is still host code: `Builder` has only `transport(RadioTransport)`, and `TransportSpec` only derives a `TransportIdentity`. | 2 | [`RadioClient.Builder.build()`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt) |
| R-8 | ~~Exponential-backoff reconnect after `TransportError`~~ | ~~internal~~ | **Done**: `Builder.autoReconnect(AutoReconnectConfig())` turns on exponential backoff with jitter and `ConnectionState.Reconnecting`. It is off by default. | — | — |
| R-9 | ~~Identity-rebind `MeshEvent.ProtocolWarning`~~ | ~~observable~~ | **Done**: the engine emits [`MeshEvent.IdentityRebound`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt) before touching storage; see [`architecture/storage.md`](./architecture/storage.md#consumer-observable-signal). | — | — |
| R-10 | Message history persistence in storage | none | Missing: the `messages` table was dropped in schema v2; history is the consumer's concern. | — (consumer-owned) | [`architecture/storage.md`](./architecture/storage.md#message-history-is-not-stored), `1.sqm` |
| R-11 | MQTT proxy transport (`transport-mqtt-proxy`) | none | Missing: named in the root README only; no design covers it. MQTT proxy mode itself works through `MeshEvent.MqttProxyMessage` and `sendRaw` ([`protocol.md` §14](./protocol.md#14-mqtt-proxy-mode)). | post-1.0 | [`README.md`](../README.md) |
| R-12 | Remote-RPC artifacts (`:rpc`, `:transport-rpc`, `:host-rpc-server`) | none | Missing | post-1.0 | [`future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md) |
| R-13 | ~~Channel and PSK management (read, list, set channels)~~ | ~~depends on R-1~~ | **Done**: `AdminApi.getChannel`, `setChannel` and `listChannels`. PSK derivation stays in firmware; the SDK carries `Channel` messages. | — | — |
| R-14 | ~~StoreForwardApi~~ | ~~public~~ | **Done**: `client.storeForward` has `requestHistory`, `requestStats`, the `servers` StateFlow and the `events` cold flow; repeated replays of one message are counted once. | — | — |
| R-15 | ~~Presence tracking (WentOffline / CameOnline)~~ | ~~public~~ | **Done**: `Builder.presenceTimeout(Duration)` enables per-node presence; `NodeChange.WentOffline` and `CameOnline` are emitted. | — | — |
| R-16 | `TransportSpec.Http` | public | Missing: the spec shape exists in [`Transport.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) but there is no HTTP transport module (`transport-http` is commented out in `settings.gradle.kts`). | post-1.0 | [`protocol.md`](./protocol.md#4-http-api-framing) |
| R-17 | `MeshEvent.KeyVerification` | public | No-op: declared, never emitted by the engine. | 2 | [`Node.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt) |
| R-18 | `MeshtasticException.FirmwareTooOld` | public | No-op: declared, never thrown; the reconnect policy only checks for it. | 2 | [`MeshtasticException.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/MeshtasticException.kt) |
| R-19 | `Storage.saveSessionPasskey` / `loadSessionPasskey` | public | Unused by the engine: the storage backends implement both, but the engine keeps passkeys in memory and never calls them. | n/a (host-facing) | [`Storage.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt) |
| R-20 | Firmware update (`FirmwareApi`) | none | Missing: omitted from the MVP. XModem file transfer ships as `RadioClient.files` ([`FileTransferApi`](./api-reference.md#filetransferapi)); firmware does not flash over it. | 6 | [`api-reference.md`](./api-reference.md#filetransferapi) |
| R-21 | iOS XCFramework and SPM publish | none | Missing: KMMBridge is pinned in `gradle/libs.versions.toml` but not applied by any build script, so no XCFramework or SPM package is published. | unscheduled | [ADR-007](./decisions/007-ios-distribution.md) |
| R-22 | Dokka documentation coverage gate | none | Missing: `explicitApi()` forces visibility, but no build step fails on an undocumented public symbol. | unscheduled | [ADR-003](./decisions/003-tooling.md) |

## How to add to this list

When you introduce a public symbol that is intentionally a stub or no-op, add a row here in the same pull request. Reviewers reject deferred public surface that has no row. When the work lands, strike the item through, mark it **Done** with what shipped, and reference the implementing pull request in its `changelog.d` fragment.

## Issue tracking

This list is not mirrored to GitHub Issues; the rows are the source of truth. A maintainer who claims an item opens an issue then and links back here. Phase 2 and 3 epics can group several R-IDs.

## Related

- [`future/`](./future/): long-form design for future work (remote RPC)
- [`versioning.md`](./versioning.md): how additive surface changes interact with SemVer
