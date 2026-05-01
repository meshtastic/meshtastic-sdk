# Roadmap — Phase 2 / Phase 3 deferred work

> Canonical inventory of features that are **declared in the public surface
> or referenced in code/docs** but are **not yet wired**. Everything here
> is intentional: the surface exists so consumers can pin against the
> shape, while the implementation lands in a later phase.
>
> Long-form forward-looking design lives in [`./future/`](./future/) (e.g.
> the wasm/RPC roadmap). This file tracks short, code-anchored deferrals.

## Status legend

- **Stub** — the symbol exists in the public API; calling it throws
  `NotImplementedError` (`TODO(...)`).
- **No-op** — the symbol exists, accepts input, but the input has no
  observable effect yet.
- **Missing** — referenced in docs/code comments only; not in the API
  surface.

## Inventory

| ID | Item | Surface | Status | Phase | Source |
|---|---|---|---|---|---|
| R-1 | ~~`RadioClient.admin` (admin RPC)~~ | ~~public~~ | **Done** — Sprint 5 (`AdminApiImpl`, [`AdminApi`](../core/src/commonMain/kotlin/org/meshtastic/sdk/AdminApi.kt)). 16 methods + `editSettings` transactional builder; `SessionKeyExpired` triggers single-shot retry. | — | — |
| R-2 | ~~`RadioClient.telemetry`~~ | ~~public~~ | **Done** — Sprint 5 ([`TelemetryApi`](../core/src/commonMain/kotlin/org/meshtastic/sdk/TelemetryApi.kt)). 5 request methods + `observe(node)` cold flow. | — | — |
| R-3 | ~~`RadioClient.routing`~~ | ~~public~~ | **Done** — Sprint 5 ([`RoutingApi`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RoutingApi.kt)). `traceRoute(dest, hopLimit)` + `requestNeighborInfo(node)`. | — | — |
| R-4 | ~~`Builder.clock(Clock)` injection~~ | ~~public, no-op~~ | **Done** — Sprint 5. `Clock` is now a `fun interface` returning `Instant`; wired into `AdminApi.setTime(at = null)` and the post-handshake auto-sync trigger. | — | — |
| R-5 | `Builder.protocolLogging(level, redactor)` | public | No-op (stored, unused) | 2 | [`RadioClient.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt) — engine frame-handling instrumentation |
| R-6 | `Builder.autoSyncTimeOnConnect(...)` | public | **Partial** — Sprint 5 fires a single fire-and-forget `AdminApi.setTime()` after handshake. Future iteration will gate on observed device skew (currently unconditional). | 2 | [`RadioClient.connect`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt) |
| R-7 | `TransportSpec`-driven transport factory lookup | public | Stub (Builder errors at `build()` if no concrete transport supplied) | 2 | [`RadioClient.Builder.build()`](../core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt) |
| R-8 | Exponential-backoff reconnect after `TransportError` | internal | Partial — opt-in `Builder.autoReconnect(...)` ships in Sprint 4; remaining gap is the always-on default and skew-aware tuning. | 3 | [`MeshEngine.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/internal/MeshEngine.kt) `handleReconnectTick` |
| R-9 | ~~Identity-rebind `MeshEvent.ProtocolWarning`~~ | ~~observable~~ | **Done** — engine emits [`MeshEvent.IdentityRebound`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt) before clearing storage; see [`architecture/storage.md`](./architecture/storage.md#consumer-observable-signal-r-9) | — | — |
| R-10 | Message history persistence in storage | none | Missing — `messages` table dropped in schema v2 (consumer concern) | — (consumer-owned) | [`architecture/storage.md`](./architecture/storage.md#message-history-is-not-stored), `migration_1__2.sqm` |
| R-11 | MQTT proxy transport (`transport-mqtt-proxy`) | none | Missing — referenced in README + `future/wasm-rpc-roadmap.md`; ships post-1.0 additively | post-1.0 | [`future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md), [`README.md`](../README.md) |
| R-12 | Wasm/RPC additive artifacts | none | Missing | post-1.0 | [`future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md) |
| R-13 | ~~Channel/PSK management (read/list/set channels)~~ | ~~depends on R-1~~ | **Done** — Sprint 5. `AdminApi.getChannel / setChannel / listChannels` ship via the same `CommandDispatcher` path. PSK derivation stays in firmware; SDK ferries `Channel` proto messages. | — | — |

## How to add to this list

When you introduce a new public symbol that is intentionally a stub or
no-op, add a row here in the same PR. Reviewers should reject any
"deferred" public surface that is not represented here. When the work
lands, delete the row and reference the implementing PR in the relevant
`CHANGELOG.md` entry.

## Issue tracking

We do not mirror this list to GitHub Issues. The roadmap entries above
are the source of truth; if a maintainer wants to claim an item they
open an issue at that point and link back here. Phase-2/3 epics may
group multiple R-IDs.

## Related

- [`SPEC.md`](./SPEC.md) §6 — phase plan with milestones
- [`future/`](./future/) — long-form forward-looking design (wasm/RPC)
- [`versioning.md`](./versioning.md) — how additive surface changes
  interact with SemVer
