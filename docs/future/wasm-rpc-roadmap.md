# Remote-RPC roadmap (post-1.0)

> **Status: roadmap, not MVP.** Tracked here so the design isn't lost between now and then.
>
> The web targets already ship: `:core`, `:testing` and `:transport-websocket` publish `js` and `wasmJs` klibs, and a browser or Node.js host runs the engine in-process over a radio's WebSocket phone API (see [`../architecture/module-graph.md`](../architecture/module-graph.md)). This roadmap covers the remote engine for hosts that cannot reach the radio themselves.

---

## Why deferred

A web host reaches a radio directly only over the WebSocket phone API. A radio on BLE, USB-serial or plain TCP, or one engine shared by several clients, needs a remote-RPC adapter that owns versioning, snapshot+delta semantics and authentication separately from the local API. Designing both surfaces at once, before the local API has been hardened by Phase 5, risks the local API being shaped by RPC concerns.

Deferring lets the local engine settle to 1.0 first, then the RPC adapter layers on top *as an additive surface* (no breaking change required to ship it).

---

## Target architecture (when it lands)

```
                       ┌─────────────────────┐
   remote client ───▶  │  :transport-rpc     │ ── WS ──▶ ┌─────────────────────┐
   (any KMP target,    │  Ktor WS client     │            │  :host-rpc-server   │
    js / wasmJs too)   │  speaks RPC envel.  │            │  Ktor server        │
                       └─────────────────────┘            │  + real :core       │
                                                          │  + real transport   │
                                                          │    (BLE / TCP / …)  │
                                                          └─────────────────────┘
```

- `:host-rpc-server` (JVM/Android) hosts a real engine and exposes it over WebSocket using a versioned envelope protocol.
- `:transport-rpc` is a `RadioTransport` *adapter* for the remote consumer: it speaks WS to a `host-rpc-server` and produces synthetic `Frame`s/`TransportState` such that `:core` cannot tell it isn't a local radio.
- `:rpc` carries the wire envelopes (snapshot + delta types, versioning).

### Key design constraints

1. **The local API does NOT change.** RPC is layered on top. `:core` does not depend on `:rpc`.
2. **Snapshot+delta semantics, not call-and-reply.** A connecting remote consumer receives one snapshot of `MeshState` (NodeDB, configs, channels, ownNode) and then deltas. Mirrors `NodeChange.Snapshot` + `Added/Updated/Removed` on the local API.
3. **Versioned envelopes.** Every frame carries a wire-protocol version. Mismatched client/server warns; major mismatch refuses to handshake.
4. **Loopback by default.** `host-rpc-server` binds `127.0.0.1` unless explicitly configured otherwise; production deployments front it with TLS.
5. **No new public sealed types in `:core`.** RPC-specific types live in `:rpc`. The local API stays unaware.

### Module additions (when shipped)

| Module | Targets | Depends on | Published |
|---|---|---|---|
| `:rpc` | all incl. `js`, `wasmJs` | `org.meshtastic:protobufs` | yes |
| `:transport-rpc` | all incl. `js`, `wasmJs` | `:rpc`, `org.meshtastic:protobufs` (NOT `:core`) | yes |
| `:host-rpc-server` | jvm, android | `:core`, `:rpc` | yes |
| `:samples/host-rpc-server` | jvm | `:host-rpc-server` | no (sample) |

`:transport-ble`, `:transport-tcp`, `:transport-serial` and `:storage-sqldelight` stay off the web targets: a browser has no socket or SQLDelight driver for them.

### Security posture (post-roadmap)

When `:host-rpc-server` ships, [`docs/security.md`](../security.md) gains a per-transport row:

| Transport | Confidentiality of link | Notes |
|---|---|---|
| **RPC (WS to host-rpc-server)** | None unless deployed behind TLS | Default loopback bind; production MUST front with TLS terminator (nginx, Caddy, native Ktor TLS). |

Authentication: a static shared-secret token in the WS upgrade headers is the MVP. Anything richer (mutual TLS, per-client tokens) is layered on by hosts.

---

## Browser-direct transports not planned as SDK modules

- **Web Bluetooth `:transport-ble`.** Web Bluetooth is Chromium-only, not on Firefox/Safari, and the spec's permission story is in flux. A web host can supply its own `RadioTransport` over it.
- **TCP/serial from a browser.** Impossible without an extension or native bridge; the WebSocket phone API is the browser's direct path.

---

## Milestones

This roadmap stays parked until **all** of:

1. The local `:core` API is at 1.0 (post Phase 7 in `SPEC.md` §6).
2. There is at least one validated consumer asking for headless-RPC support.

Then: open ADR-008 to formally accept this design (it stays a roadmap doc until that ADR lands), do a minor release adding `:rpc` + `:transport-rpc` + `:host-rpc-server`.

---

## Related

- [`../SPEC.md`](../SPEC.md) §0 — "What this SDK is NOT (MVP)" defers RPC.
- [`../SPEC.md`](../SPEC.md) §6 "Future (post-1.0, non-breaking adds)" — the staging area for this roadmap.
- [`../decisions/006-multi-module-rationale.md`](../decisions/006-multi-module-rationale.md) — current MVP module list.
- [`../security.md`](../security.md) — current per-transport posture; gains an RPC row when this roadmap lands.
