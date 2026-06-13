# Wasm + remote-RPC roadmap (post-1.0)

> **Status: roadmap, not MVP.** Lands when the upstream KMP wasm story matures (stable `wasmJs` `kotlinx-io`/`kotlinx-coroutines`/`kotlinx-datetime` parity, stable Compose/Wire interop, mature browser BLE story or a generally-accepted RPC pattern in the KMP ecosystem). Tracked here so the design isn't lost between now and then.
>
> Until this roadmap lands, `meshtastic-sdk` ships only `androidTarget`, `jvm`, and `ios{Arm64,X64,SimulatorArm64}` (per `SPEC.md` §1, §0 "What this SDK is NOT (MVP)"). Browser consumers should expect to wait until 1.x.y.

---

## Why deferred

`SPEC.md` §0 rule "What this SDK is NOT (MVP)" defers wasm + RPC for two compounding reasons:

1. **Tooling maturity.** `wasmJs` is alpha-grade across a number of dependencies the engine relies on (browser-specific quirks in `kotlinx-coroutines` schedulers, `kotlinx-io` byte-handling, Wire's runtime on wasm). Locking the API surface to wasm now would force backward-compatible workarounds before the platform is stable.
2. **Architectural cost up-front.** A wasm consumer cannot run the engine in-process (no BLE, no socket, no SQLDelight on browser today). It therefore needs a remote-RPC adapter that owns versioning, snapshot+delta semantics, and authentication separately from the local API. Designing both surfaces simultaneously, before the local API has been hardened by Phase 5, risks the local API being shaped by RPC concerns.

Deferring lets the local engine settle to 1.0 first, then the RPC adapter layers on top *as an additive surface* (no breaking change required to ship it).

---

## Target architecture (when it lands)

```
                       ┌─────────────────────┐
   browser / wasm ───▶ │  :transport-rpc     │ ── WS ──▶ ┌─────────────────────┐
   (any KMP wasmJs     │  Ktor WS client     │            │  :host-rpc-server   │
    consumer)          │  speaks RPC envel.  │            │  Ktor server        │
                       └─────────────────────┘            │  + real :core       │
                                                          │  + real transport   │
                                                          │    (BLE / TCP / …)  │
                                                          └─────────────────────┘
```

- `:host-rpc-server` (JVM/Android) hosts a real engine and exposes it over WebSocket using a versioned envelope protocol.
- `:transport-rpc` is a `RadioTransport` *adapter* for the wasm/remote consumer: it speaks WS to a `host-rpc-server` and produces synthetic `Frame`s/`TransportState` such that `:core` cannot tell it isn't a local radio.
- `:rpc` carries the wire envelopes (snapshot + delta types, versioning).

### Key design constraints

1. **The local API does NOT change.** RPC is layered on top. `:core` does not depend on `:rpc`.
2. **Snapshot+delta semantics, not call-and-reply.** A connecting wasm consumer receives one snapshot of `MeshState` (NodeDB, configs, channels, ownNode) and then deltas. Mirrors `NodeChange.Snapshot` + `Added/Updated/Removed` on the local API.
3. **Versioned envelopes.** Every frame carries a wire-protocol version. Mismatched client/server warns; major mismatch refuses to handshake.
4. **Loopback by default.** `host-rpc-server` binds `127.0.0.1` unless explicitly configured otherwise; production deployments front it with TLS.
5. **No new public sealed types in `:core`.** RPC-specific types live in `:rpc`. The local API stays unaware.

### Module additions (when shipped)

| Module | Targets | Depends on | Published |
|---|---|---|---|
| `:rpc` | all incl. `wasmJs` | `org.meshtastic:protobufs` | yes |
| `:transport-rpc` | all incl. `wasmJs` | `:rpc`, `org.meshtastic:protobufs` (NOT `:core`) | yes |
| `:host-rpc-server` | jvm, android | `:core`, `:rpc` | yes |
| `:samples/wasm-app` | `wasmJs` | `:core`, `:transport-rpc` | no (sample) |
| `:samples/host-rpc-server` | jvm | `:host-rpc-server` | no (sample) |

Existing modules grow a `wasmJs` source set only where they have no platform binding to native libs (`:core`'s public surface where reachable; the `org.meshtastic:protobufs` types are already KMP). `:transport-ble`, `:transport-tcp`, `:transport-serial-*`, `:storage-sqldelight` do NOT add `wasmJs` — there's no mature browser equivalent.

### Per-target compile matrix (post-roadmap)

| Module | android | jvm | iosArm64 | iosX64 | iosSimulatorArm64 | wasmJs |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| `:core` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:rpc` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:transport-rpc` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:transport-ble` | ✓ | — | ✓ | ✓ | ✓ | — |
| `:transport-tcp` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `:transport-serial-android` | ✓ | — | — | — | — | — |
| `:transport-serial-jvm` | — | ✓ | — | — | — | — |
| `:storage-sqldelight` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `:host-rpc-server` | ✓ | ✓ | — | — | — | — |
| `:testing` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

### Security posture (post-roadmap)

When `:host-rpc-server` ships, [`docs/security.md`](../security.md) gains a per-transport row:

| Transport | Confidentiality of link | Notes |
|---|---|---|
| **RPC (WS to host-rpc-server)** | None unless deployed behind TLS | Default loopback bind; production MUST front with TLS terminator (nginx, Caddy, native Ktor TLS). |

Authentication: a static shared-secret token in the WS upgrade headers is the MVP. Anything richer (mutual TLS, per-client tokens) is layered on by hosts.

---

## Browser-direct alternatives explicitly NOT planned

- **Web Bluetooth `:transport-ble`.** Web Bluetooth is Chromium-only, not on Firefox/Safari, and the spec's permission story is in flux. RPC is the herd default for "browser → server-owned hardware" anyway.
- **Direct TCP/serial from browser.** Impossible without an extension or native bridge.

---

## Milestones

This roadmap stays parked until **all** of:

1. `wasmJs` source sets reach stable status across `kotlinx-coroutines`, `kotlinx-io`, `kotlinx-datetime`.
2. Wire (or equivalent) emits Kotlin protobuf code that compiles cleanly to `wasmJs`.
3. The local `:core` API is at 1.0 (post Phase 7 in `SPEC.md` §6).
4. There is at least one validated consumer asking for browser/headless-RPC support.

Then: open ADR-008 to formally accept this design (it stays a roadmap doc until that ADR lands), do a minor release adding `:rpc` + `:transport-rpc` + `:host-rpc-server` + `wasmJs` source sets where applicable.

---

## Related

- [`../SPEC.md`](../SPEC.md) §0 — "What this SDK is NOT (MVP)" defers wasm/RPC.
- [`../SPEC.md`](../SPEC.md) §6 "Future (post-1.0, non-breaking adds)" — the staging area for this roadmap.
- [`../decisions/006-multi-module-rationale.md`](../decisions/006-multi-module-rationale.md) — current MVP module list.
- [`../security.md`](../security.md) — current per-transport posture; gains an RPC row when this roadmap lands.
