# Remote-RPC roadmap (post-1.0)

This page records the design for a remote engine, for hosts that cannot reach the node themselves. It is a roadmap, not shipped work.

The web targets already ship: `:core`, `:testing` and `:transport-websocket` publish `js` and `wasmJs` klibs, and a browser or Node.js host runs the engine in-process over a node's WebSocket phone API ([`../architecture/module-graph.md`](../architecture/module-graph.md)).

---

## Why deferred

A web host reaches a node directly over the WebSocket phone API, or over Web Bluetooth or Web Serial (Chromium only) with a `RadioTransport` it supplies. A node on plain TCP, a browser without those APIs, or one engine shared by several clients needs a remote-RPC adapter that owns versioning, snapshot-and-delta semantics and authentication apart from the local API. Designing both surfaces at once risks shaping the local API around RPC concerns.

Deferring lets the local engine settle at 1.0 first; the RPC adapter then layers on top as an additive surface, with no breaking change.

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
- **Web Serial `:transport-serial`.** Web Serial is Chromium-only, like Web Bluetooth. A web host can supply its own `RadioTransport` over it.
- **Raw TCP from a browser.** Impossible without an extension or native bridge; the WebSocket phone API is the browser's network path.

---

## Milestones

This roadmap stays parked until **all** of:

1. The local `:core` API is at 1.0.
2. At least one validated consumer asks for headless-RPC support.

Then an ADR accepts this design (it stays a roadmap page until then), and a minor release adds `:rpc`, `:transport-rpc` and `:host-rpc-server`.

---

## Related

- [`../SPEC.md`](../SPEC.md) §0, "What this SDK is NOT (MVP)", defers RPC.
- [`../SPEC.md`](../SPEC.md) §6, "Future (post-1.0, non-breaking adds)", is the staging area for this roadmap.
- [`../decisions/006-multi-module-rationale.md`](../decisions/006-multi-module-rationale.md): the module list.
- [`../security.md`](../security.md): per-transport posture, which gains an RPC row when this roadmap lands.
