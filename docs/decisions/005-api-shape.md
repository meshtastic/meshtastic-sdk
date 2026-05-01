# ADR 005 — Public API shape: response shapes, NodeChange, MessageHandle, ConnectionState, backpressure, multi-radio

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads
**Supersedes:** none
**Related:** [`../SPEC.md`](../SPEC.md) §3, ADR-001 (proto types are the data model), ADR-002 (engine architecture), [`../protocol.md`](../protocol.md)

---

## Context

ADR-001 settled the *data* shape of the public API: Wire-generated protobuf types are the domain model. This ADR settles the *operations* shape: how callers invoke the SDK, how they observe streams, how they handle errors, and what guarantees the SDK provides around lifecycle, backpressure, and multi-device use.

The audience is mixed:

- Kotlin/Android app authors who want suspending functions and `Flow`.
- Swift app authors who consume the iOS framework via `try await` and `AsyncSequence` (with KMP-NativeCoroutines or skie-style sugar in the host app).
- JVM bridge authors writing headless gateways or test rigs.
- Wasm/JS authors going through a remote-RPC adapter (out of scope for this ADR; see `transport-rpc` charter).

A naive "everything returns `Result<T>`" or "everything throws" design fails one or more of these audiences. We need a deliberate split.

## Decision

### Three response shapes — and only three

| Shape | When |
|---|---|
| **Throwing `suspend`** (`@Throws(MeshtasticException::class, CancellationException::class)`) | Lifecycle and programmer errors: `connect()`, `disconnect()`, `send()` (synchronous validation), `nodeSnapshot()`. These are situations where retrying without changing inputs is pointless. Bridges naturally to Swift `try await`. |
| **`AdminResult<T>` (sealed)** | Routine radio outcomes for admin/RPC ops: `getConfig`, `setConfig`, `setOwner`, `traceRoute`, `requestEnvironment`, … NAKs and timeouts are *expected* on a flaky mesh and shouldn't unwind the stack. |
| **`Flow` / `StateFlow`** | Streams: `connectionState`, `ownNode`, `nodes`, `packets`, `events`. |

We **never** use `kotlin.Result<T>` in any public API — it doesn't bridge to Swift, leaks the Kotlin-stdlib `Result` type into ABI, and makes pattern matching weaker than a sealed hierarchy. KGP `checkKotlinAbi` catches the leak in the API surface; detekt's `ForbiddenImport` rule provides an explicit hint at PR time (see ADR-008).

### `MessageHandle` — outbound packet lifecycle

`send(packet)` returns immediately with a `MessageHandle` containing:

- `id: MessageId` — the `request_id` allocated by the engine.
- `state: StateFlow<SendState>` — observe transitions: `Queued → Sent → (Acked | Delivered | Failed(reason))`.
- `suspend fun await(): SendOutcome` — suspends until terminal.
- `fun cancel()` — best-effort.

Invariants:

1. **Disconnect resolves all open handles.** If the engine disconnects (transport drop, `client.disconnect()`, supervisor cancel) while any handle is non-terminal, the engine sets `state = Failed(Disconnected)` for every open handle before tearing down. `await()` returns the corresponding `SendOutcome`. No `await()` coroutine leaks.
2. **Caller cancellation is independent of handle.** If the *caller's* coroutine is cancelled while suspended in `await()`, the function rethrows `CancellationException`; the handle itself continues to track the send (other observers of `state` see updates as normal). Use `MessageHandle.cancel()` to actively withdraw.
3. **`cancel()` is idempotent and state-dependent.** Pre-`Sent`: removed from the host outbound queue; `state = Failed(Cancelled)`. Post-`Sent`: no effect on the radio (device + mesh continue); state unchanged. Always safe to call.
4. **`PayloadTooLarge` is not a `SendFailure`.** It is thrown synchronously from `send()` as `MeshtasticException.PayloadTooLarge`. A handle is never returned in that case. The device-side `Routing.Error.TOO_LARGE` (should it ever escape pre-validation due to a firmware schema bump) maps to `SendFailure.Other(routingError = TOO_LARGE)`.

### `NodeChange` — delta stream with snapshot replay

`nodes: Flow<NodeChange>` instead of `StateFlow<Map<NodeId, NodeInfo>>`:

```kotlin
public sealed interface NodeChange {
    public data class Snapshot(val nodes: Map<NodeId, NodeInfo>) : NodeChange  // first emission only
    public data class Added(val node: NodeInfo) : NodeChange
    public data class Updated(val node: NodeInfo, val changed: Set<NodeField>) : NodeChange
    public data class Removed(val nodeId: NodeId) : NodeChange
}
```

Contract:

- **Every new subscriber gets exactly one `Snapshot` first**, then live deltas.
- The `Snapshot` is a coherent point-in-time view (taken under the engine actor, see ADR-002), and subsequent deltas are causally ordered on top of it.
- **Deltas MUST NOT drop.** The backing `MutableSharedFlow` uses `extraBufferCapacity = 256` with `SUSPEND` overflow. Slow consumers backpressure the engine, which routes pressure to the inbox.

Rationale: a 200-node mesh emitting telemetry every 30 s would push a 200-entry map ~7 times/sec under `StateFlow`. Deltas are O(1). Subscribers wanting a `StateFlow` can fold trivially.

### `ConnectionState` — explicit set, no `DeviceSleep`

```kotlin
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data class Connecting(val attempt: Int) : ConnectionState
    public data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState
    public data object Connected : ConnectionState
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int) : ConnectionState
}
```

There is no `DeviceSleep` state. Devices do not announce sleep on the wire — `PhoneAPI` simply goes silent — so the SDK cannot reliably distinguish "device is sleeping for `ls_secs`" from "transport is hung". Sleep timing IS observable via `Config.power.ls_secs` from the handshake snapshot; when the device stops responding, the state transitions through `Reconnecting` exactly as for any other disconnect. Hosts that care about sleep-vs-error inspect the `cause` field.

`Connected` is reached **only** after the Stage 2 `config_complete_id` matches the Stage 2 nonce (`protocol.md` §6). Until then: `Connecting` or `Configuring`.

### Backpressure policy

Per `SPEC.md` §4.4:

| Flow | Buffer | Overflow |
|---|---|---|
| `connectionState`, `ownNode` | conflated `MutableStateFlow` | n/a |
| `nodes` | 256 | `SUSPEND` (deltas MUST NOT drop) |
| `packets` | 128 | `SUSPEND`; if engine inbox itself fills, engine drops oldest queued frame and emits `MeshEvent.PacketsDropped(Packets, n)` |
| `events` | 64 | `DROP_OLDEST`; drop bursts surface as `PacketsDropped(Events, n)` on the next event |

Silent loss is forbidden in the public surface. Drops are observable.

### `TransportIdentity` — fragmentation caveat

Storage is keyed by `TransportIdentity`, derived from `TransportSpec`. `Of(spec)` lower-cases TCP host and HTTP base URL to absorb the obvious case-only divergence; everything else is a literal echo of the consumer's input.

Caveat: connecting to `meshtastic.local` and `192.168.1.42` produces two distinct identities for the same physical radio. The SDK does not perform DNS canonicalisation (DNS is platform/network-dependent and could yield a different cache key on every connect). Mitigations:

- Consumers wanting one logical store across address changes canonicalise themselves before constructing `TransportSpec.Tcp/Http`, OR
- The engine catches the post-handshake `recordOwnNode` call: if the storage's prior NodeNum differs from the current one for this identity (factory reset, swap, hostname now points at a different radio), `DeviceStorage.recordOwnNode` MUST atomically `clear()` and persist the new tuple. The engine then rebuilds `MeshState` from the fresh handshake. `MeshEvent.ProtocolWarning("identity rebound to new NodeNum")` surfaces the rebind.

### Multi-radio = one `RadioClient` per radio

A single `RadioClient` owns exactly one `TransportSpec` and one `DeviceStorage` for its lifetime. Hosts talking to N radios concurrently instantiate N clients (each with its own `Builder.storage(...)` and `Builder.transport(...)`); they share nothing. The SDK does not multiplex one client over multiple transports — the engine actor's single-writer invariant (ADR-002) and storage's per-identity activation both presume a 1:1 client↔radio relationship.

Hosts that want a single observable view across radios fan-in flows themselves with `combine`/`merge`.

### `AdminApi.setTime` and `autoSyncTimeOnConnect`

Routers and headless devices without GPS rely on the phone for clock sync (`protocol.md` §19.17). The API provides:

- `AdminApi.setTime(at: Instant = Clock.System.now()): AdminResult<Unit>` — push the host clock to the device as `set_time_only`.
- `Builder.autoSyncTimeOnConnect(enabled: Boolean)` — default `true`. After Stage 2 completes, if the device's reported clock differs from the host's by more than 60 s, the engine calls `setTime()` automatically.

### `:core` packages contract

- `org.meshtastic.sdk` — the entire public API (`RadioClient`, `MessageHandle`, `SendState`, `NodeChange`, `ConnectionState`, `TransportSpec`, `TransportIdentity`, `MeshtasticException`, value-class IDs).
- `org.meshtastic.proto.*` — Wire-generated types, re-exported from the `:proto` module so consumers don't add a second dependency.
- Anything under `org.meshtastic.sdk.internal.*` is `internal` Kotlin and not part of the API surface.

`:core` does not depend on `:rpc` (Gradle dep graph + `:core:verifyModuleBoundary`).

## Alternatives considered

- **`Result<T>` everywhere.** Rejected — `kotlin.Result` doesn't bridge to Swift, and pattern-matching `Result` is weaker than `AdminResult`'s sealed cases (we lose the `SessionKeyExpired` / `Unauthorized` / `NodeUnreachable` distinctions).
- **Throwing for everything.** Rejected — admin NAKs and mesh timeouts are *expected* on a flaky mesh; turning them into exceptions makes routine consumer code an `try { … } catch { … }` ladder.
- **`StateFlow<Map<NodeId, NodeInfo>>` for `nodes`.** Rejected — see NodeChange rationale above.
- **`DROP_OLDEST` on `packets`.** Rejected — silent text-message loss is unacceptable. `SUSPEND` + observable `PacketsDropped` is the consumer-friendly behavior.
- **Add `DeviceSleep` to `ConnectionState`.** Rejected — no wire trigger; would require timer heuristics that mis-classify transport hangs.
- **Multiplex multi-radio in one client.** Rejected — breaks the single-writer engine invariant; storage keying becomes ambiguous; lifecycle tangles. Two clients are simpler, share no engine state, and let consumers reason per-radio.
- **DNS-canonicalise TCP host.** Rejected — would change the cache key on every IP rotation; surprises consumers who picked `meshtastic.local` precisely because they wanted the address to vary.

## Consequences

- **Swift consumption is first-class.** Throwing-suspend bridges to `try await`; sealed `AdminResult` enums map cleanly to Swift; `Flow` works via KMP-NativeCoroutines / skie at the host app's choice.
- **Pre-1.0 API churn is bounded by this ADR plus `SPEC.md` §3.** Any change to the response shapes, NodeChange contract, MessageHandle invariants, or backpressure policy is a SemVer-major signal even pre-1.0 and warrants a follow-up ADR.
- **Backpressure is observable.** Hosts can render a "you're falling behind" UI hint by collecting `events` for `PacketsDropped`.
- **No silent state corruption on factory reset.** The NodeNum-mismatch reset rule, surfaced as `ProtocolWarning`, prevents stale node DBs from leaking into a new device's session.
- **Two clients for two radios is the recommended pattern.** Documented in the README and the multi-radio sample.
