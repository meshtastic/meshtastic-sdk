# ADR 002 — Engine architecture: single-writer actor over pluggable transport

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads
**Supersedes:** none
**Related:** [`../SPEC.md`](../SPEC.md) §4, [`../protocol.md`](../protocol.md) §6, ADR-001 (proto types), ADR-005 (API shape), ADR-006 (multi-module split)

---

## Context

The Meshtastic PhoneAPI is a small but stateful protocol:

- A single bidirectional byte-stream per device (BLE notify-and-write, TCP socket, serial UART, or HTTP poll).
- A two-stage configuration handshake on every connect (`protocol.md` §6) before any normal traffic is meaningful.
- Long-lived NodeDB and channel state that mutate on every `node_info`, `Routing`, `QueueStatus`, `Telemetry`, etc.
- Per-`request_id` admin RPCs that interleave with broadcast traffic on the same wire.
- Device-side mesh retry — the SDK MUST NOT re-enqueue mesh packets; it observes outcomes only (`protocol.md` §11).
- Backpressure: a slow consumer of `packets`/`nodes` MUST NOT stall the transport reader.

Naive designs that distribute mutation across multiple coroutines lead to surprising orderings — ACK arriving before `Sent`, NodeDB snapshot stitched mid-update, channel-key arrival racing with deferred-decrypt. We have direct precedent: the early `Meshtastic-Android` `MeshService` mixed lifecycle scopes and required several rounds of mutex hardening to stabilize.

## Decision

The engine is a **single-writer actor**. Exactly one coroutine — the engine coroutine — owns and mutates all engine state (NodeDB, channels, configs, in-flight handle map, queue indexes). Every input — frames in, public method calls, transport state changes, timers — is encoded as an `EngineMessage` and posted to one unbounded `Channel<EngineMessage>` that the engine drains in order.

```
                                   ┌──────────────────────────────────┐
 RadioClient.send() / connect() ──▶│                                  │
 transport.frames() (reader)     ──▶│ Engine actor (one coroutine)     │──▶ MutableStateFlow / SharedFlow → public Flows
 transport.state                 ──▶│ owns NodeDB, queue, handle map   │──▶ transport.send(frame)  (sequential FIFO)
 timers (heartbeat, handshake)   ──▶│                                  │──▶ DeviceStorage  (suspend writes serialised by actor)
                                   └──────────────────────────────────┘
```

### Coroutine roster

Per active `RadioClient`:

| Coroutine | Purpose | Cardinality |
|---|---|---|
| **Engine actor** | Drains `Channel<EngineMessage>(UNLIMITED)`. Owns all state. The only writer to public flows. Runs the handshake FSM inline (not in a separate coroutine). | 1 |
| **Frame reader** | Collects `transport.frames()`, wraps each into `EngineMessage.FrameRx`, posts to engine inbox. | 1 per active transport |
| **Outbound writer** | Drains `Channel<Frame>` populated by the engine, calls `transport.send(frame)` sequentially. Separated from the engine so a slow socket cannot block engine progress. | 1 |
| **Transport state observer** | Collects `transport.state` flow, wraps each transition into `EngineMessage.TransportStateChanged`, posts to engine inbox. | 1 |
| **Heartbeat scheduler** | Fires `EngineMessage.HeartbeatTick` every 30 s while connected. Started after `ConnectionState.Connected` only. Per-transport policy (`protocol.md` §16). | 1, lifetime = connected only |

All five live under a single `SupervisorJob` in the `RadioClient`'s `coroutineContext` (caller-supplied via Builder). `client.disconnect()` cancels the supervisor; the engine's `finally` flushes storage and closes the transport.

### Synchronisation rule

> **No `Mutex`, no atomics, no thread-locks anywhere in the engine package.** The actor IS the synchronization primitive. If you reach for `Mutex` you are designing the wrong solution.

Exceptions outside the engine package are tolerated and small:
- `MessageHandle.state` is a `MutableStateFlow` (atomically updated by the engine).
- `WireCodec` resync state (§2 of protocol.md) is per-frame-reader and not shared.
- `atomicfu` is allowed for engine-internal counters (`request_id`, heartbeat nonce) where a single-writer guarantee is documented at the call site.

### Buffering and backpressure

Per `SPEC.md` §4.4 (codified via the §3 review pass):

| Public flow | Backing | Buffer | Overflow |
|---|---|---|---|
| `connectionState`, `ownNode` | `MutableStateFlow` | 1 (conflated) | n/a |
| `nodes: Flow<NodeChange>` | custom (snapshot replay + delta `MutableSharedFlow`) | 256 | `SUSPEND` (deltas MUST NOT drop) |
| `packets: Flow<MeshPacket>` | `MutableSharedFlow(replay=0)` | 128 | `SUSPEND`; engine inbox absorbs back-pressure; if engine inbox itself fills (UNLIMITED, so realistically only memory-bound) the engine emits `MeshEvent.PacketsDropped(Packets, n)` rather than block the transport reader |
| `events: Flow<MeshEvent>` | `MutableSharedFlow(replay=0)` | 64 | `DROP_OLDEST`; drops surface as `PacketsDropped(Events, n)` |

The transport reader **never blocks** on a slow consumer of public flows. Either back-pressure routes through the engine inbox (preferred — engine can flow-control transports that support it, or shed packets observably), or the engine drops with an emitted event (advisory).

### Component breakdown

`SPEC.md` §4.2 lists the components inside the engine. Repeated here for stability:

| Component | Responsibility |
|---|---|
| `WireCodec` | Pure encode/decode; `ToRadio` ↔ framed bytes ↔ `FromRadio`. Resync per protocol.md §2.2. No IO. |
| `RadioTransport` (interface) | Per `SPEC.md` §3.4. Implementations live in transport modules. |
| `HandshakeMachine` | Two-stage FSM (Stage 1 nonce → settle → heartbeat → settle → Stage 2 nonce → seed `session_passkey` via `get_owner_request`). |
| `MeshState` | Holds `Map<NodeId, NodeInfo>`, `ownNode`, `channels`, `ConfigBundle`, monotonic version counter. Single-writer. |
| `CommandDispatcher` | Allocates monotonic `request_id`s, parks `CompletableDeferred<AdminResult<*>>` per id, applies per-op timeouts, recognizes `ROUTING_APP` payloads addressed back as ACK/NAK for any in-flight request. |
| `MessageQueue` | Tracks outbound `MessageHandle`s. Updates `SendState` from `QueueStatus`, `Routing` ACK, or device-emitted Routing error. Does NOT retry mesh delivery. May retry the host-transport write once on `TransportState.Error(recoverable=true)`. |
| `DeferredDecryptBuffer` | Bounded ring (default 64 packets) for `MeshPacket.encrypted` with unknown channel hash. Re-attempts decrypt when a `Channel`/`Config.security` arrives that adds a key. |
| `PersistenceCoordinator` | Calls `StorageProvider.activate(identity)` BEFORE `transport.connect()`. After handshake, calls `recordOwnNode(...)`; if storage detects a NodeNum mismatch and clears (invariant 4), emits `MeshEvent.ProtocolWarning("identity rebound to new NodeNum")`. On disconnect, flushes and closes storage. |

Each component is a regular class, not a `Mockable` interface, unless tests demand otherwise. We rejected the earlier "interface-everywhere" approach: it added indirection that obscured the actor model and produced no observable testability win.

### Anchor invariants

Per `SPEC.md` §4.3, encoded as tests in `core/src/commonTest/.../invariants/`:

1. FIFO frame order (single-writer pipe to transport).
2. Transport-up ≠ session-ready (`Connected` only after `config_complete_id` matches Stage 2 nonce).
3. Pre-handshake bytes discarded (all transports).
4. Storage activated before `transport.connect()`. NodeNum mismatch ⇒ `clear()` before persisting new tuple.
5. Handshake is an explicit FSM.
6. Admin requests are idempotent and `request_id`-correlated. Per-op timeouts.
7. Mesh delivery retries belong to the device.
8. Heartbeat policy per-transport (TCP+Serial mandatory, BLE opportunistic).
9. Deferred decrypt is bounded.
10. `PayloadTooLarge` is a thrown exception, never a `SendFailure`.

## Alternatives considered

- **Lock-based shared state with multiple writers.** Rejected: every Meshtastic client that has tried this has gone through several rounds of races (Android `MeshService` history; early Apple `BLEManager`). The cost of a Mutex per state segment compounds; ordering bugs survive code review.
- **Per-component coroutines (`HandshakeMachine` as its own actor, `MessageQueue` as another, etc.).** Rejected: introduces N message buses, makes invariants like "ACK arrives strictly after Sent" require cross-actor ordering proofs. One actor with all state is dramatically simpler to reason about, and the protocol's throughput (single device, ~10–100 msgs/sec peak) doesn't justify the parallelism budget.
- **Reactive Streams / `Flow.scan` over the raw `transport.frames()`.** Rejected: pure-Flow composition can't model timeouts, request/response correlation, or the handshake FSM cleanly. State machines are clearer as state machines.
- **Coroutine `Mutex` instead of an actor.** Rejected: a Mutex protects state but provides no ordering across operations. The actor enforces both.

## Consequences

- **Easier to reason about, harder to extend ad-hoc.** Adding a new background behavior means defining an `EngineMessage` variant and handling it in the engine's `when` — never sneaking in a side-coroutine that mutates state.
- **Single hotspot.** All engine work serializes. We accept this; PhoneAPI throughput does not approach the limit.
- **Testable without mocks.** Drive the engine with a `Channel<EngineMessage>` directly from tests; assert on emitted public-flow values. No transport mocking needed for state-machine tests.
- **Cancellation is cheap.** Cancel one supervisor; everything stops; engine `finally` cleans up.
- **`disconnect()` semantics are tight.** Idempotent, never throws (per `SPEC.md` §3.1). Pending `MessageHandle`s resolve to `Failed(Disconnected)` via the engine's shutdown sequence (ADR-005).
