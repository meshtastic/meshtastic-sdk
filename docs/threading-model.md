# Threading Model

This document is the contributor-facing summary of *who runs on what
thread* in `meshtastic-sdk`. The architectural decisions live in ADRs:

- [ADR-002](decisions/002-architecture.md) — engine is a single-writer
  actor.
- [ADR-008](decisions/008-architecture-enforcement.md) — how the actor
  invariant is enforced (detekt `ForbiddenImport` + Gradle).
- [ADR-012](decisions/012-transport-threading.md) — transport-side
  threading contract.

Read those for *why*. This document is the *what*.

## Three layers

```
┌─────────────────────────────────────────────────────────────────┐
│ Consumer code (your app)                                        │
│   Calls suspend functions on RadioClient from any dispatcher.   │
│   Collects flows on its own dispatcher.                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ :core engine — single-writer actor                              │
│   ▸ All state mutations go through one Channel.                 │
│   ▸ No Mutex / Semaphore / ReentrantLock anywhere on the path.  │
│   ▸ Engine dispatcher is single-threaded by construction.       │
│   ▸ Two atomicfu sites (supervisorJobRef, nextIdCounter) are    │
│     boundary handles, not state — see inline comments.          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Transport modules — bridge native callbacks/threads to flows    │
│   ▸ TCP : Ktor sockets, fully suspending.                       │
│   ▸ BLE : Kable/CoreBluetooth callbacks → MutableSharedFlow.    │
│   ▸ Serial-Android : usb-serial-for-android callbacks → flow.   │
│   ▸ Serial-JVM : jSerialComm blocking reads on a dedicated      │
│     thread → Channel → flow.                                    │
│   May use atomics for native-handle ownership; MUST NOT block   │
│   on the engine dispatcher.                                     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Storage (:storage-sqldelight, custom DeviceStorage impls)       │
│   ▸ Every suspend method wraps its body in                      │
│     withContext(dispatcher) so blocking JDBC / Android SQLite / │
│     native sqlite3 calls run off the engine actor.              │
│   ▸ Default dispatcher is Dispatchers.IO.limitedParallelism(4)  │
│     on JVM/Android; Dispatchers.Default.limitedParallelism(4)   │
│     on Apple (Dispatchers.IO on K/N is shadowed by an internal  │
│     member, unreachable from outside kotlinx.coroutines).       │
│   ▸ See docs/architecture/storage.md § Thread-safety.           │
└─────────────────────────────────────────────────────────────────┘
```

## The actor invariant

The engine owns its state. Consumers and transports talk to it by sending
messages (suspend calls or flow emissions). Internally, the engine
processes messages serially from a single coroutine. That gives us:

- **No race conditions on engine state** — no two coroutines ever observe
  partially-applied mutations.
- **Deterministic test semantics** — `runTest { … }` advances the engine
  one message at a time.
- **No reentrancy hazards** — the engine never calls back into a
  transport while holding state locks, because there are no state locks.

The invariant is enforced by detekt (`config/detekt/detekt.yml`
`ForbiddenImport` rules ban `kotlinx.coroutines.sync.{Mutex,Semaphore}`
and `java.util.concurrent.locks.ReentrantLock`) and by the
[`docs/architecture/enforcement.md`](architecture/enforcement.md) matrix.

## What you may write

| You're writing… | You may use | You must avoid |
|---|---|---|
| Engine code (`:core`, on the engine dispatcher) | `Channel`, `Flow`, suspend functions, plain `var`s owned by the actor | `Mutex`, `Semaphore`, `ReentrantLock`, `synchronized {}`, `AtomicReference` for state, `Thread.sleep` |
| Transport code (`:transport-*`) | Whatever the native API forces (callbacks, blocking reads on a dedicated thread, atomics for handle ownership) | Blocking the engine dispatcher; calling engine internals; sharing mutable state across the transport ↔ engine boundary other than through the documented `Flow` + `suspend send` interface |
| Consumer code (your app) | Any dispatcher you like | Assuming the engine runs on `Main`; assuming flows are hot |

## How to add a new transport

See the `transport-module-authoring` skill in
[`.github/skills/`](../.github/skills/) and the existing implementations
under `transport-tcp/`, `transport-ble/`, `transport-serial/`. The
contract you implement is small: emit a `Flow<ByteString>`, accept a
`suspend fun send(ByteString)`, and respect the [ADR-012](decisions/012-transport-threading.md)
threading rules.

## Verifying

```bash
./gradlew detekt :core:verifyModuleBoundary
```

Both run as part of `./gradlew check`. The engine-dispatcher
non-blocking property is exercised by the engine test suite running on
a single-thread dispatcher under `runTest`.

---

## Consumer-side threading guide

The contract above (engine = single-writer actor, transports = bridge
adapters) is binding on contributors. *This* section documents the
consumer-facing surface — what guarantees the SDK gives your app, and
which footguns to avoid.

### Hot vs cold flows

`RadioClient` exposes three reactive surfaces. All are **hot**: they run
whether or not anyone is collecting.

| Property | Type | Replay | Buffer | Overflow policy |
|---|---|---|---|---|
| `connection` | `StateFlow<ConnectionState>` | Always current value | n/a | n/a (conflated) |
| `ownNode` | `StateFlow<NodeInfo?>` | Always current value | n/a | n/a (conflated) |
| `nodes` | `Flow<NodeChange>` (SharedFlow internally) | 1 | 256 | drop-oldest → `MeshEvent.PacketsDropped(Nodes)` |
| `packets` | `Flow<MeshPacket>` | 0 | 128 | drop-oldest → `MeshEvent.PacketsDropped(Packets)` |
| `events` | `Flow<MeshEvent>` | 0 | 64 | drop-oldest → log only (no recursive `Dropped`) |

Late subscribers to `packets` and `events` see only emissions made
**after** they start collecting; for `nodes`, late subscribers receive
the most recent change but not the full history (use
`RadioClient.snapshot()` to bootstrap, then collect to follow).

### Dispatcher choice (consumer side)

You may collect on any dispatcher. Common patterns:

- **Android UI**: collect on `viewModelScope` with `repeatOnLifecycle`
  — see [`reactive-lifecycle-management.md`](consumer-guides/reactive-lifecycle-management.md).
  Internally the SDK does not pin to `Dispatchers.Main`; you choose.
- **iOS / SwiftUI**: collect on `Dispatchers.Main` (Kotlin/Native maps
  to the main queue). Do not block in the collector — long work belongs
  on `Dispatchers.Default`.
- **JVM headless / CLI / server**: any dispatcher. Recommended pattern
  is one `CoroutineScope` per `RadioClient`, cancelled in lockstep.

The engine's internal dispatcher is **single-thread `Default`-backed**
on every platform (no `Main` assumption). Your collector dispatcher
does not have to match.

### Lifecycle and cancellation footguns

- `RadioClient.connect()` suspends until the engine reaches
  `ConnectionState.Connected`. **Cancelling the caller scope after
  connect returns does not disconnect the radio** — call
  `client.close()` for that. `close()` is idempotent and suspends
  until cleanup completes.
- A closed `RadioClient` is **not reusable**. Build a new one via the
  `Builder`.
- Heartbeats are owned by the engine, not by your collector. Cancelling
  a flow collection does not stop heartbeats; only `close()` does.
- `connect()` itself is cancellable; cancellation during connect is
  fully unwound (including any in-flight transport handshake).

### Backpressure and dropped events

The engine never blocks on consumer collection. If your collector is
slow, the SharedFlow buffers fill and **drop-oldest** semantics kick
in. You will receive a `MeshEvent.PacketsDropped(flow, droppedCount)`
on `events` whenever this happens for `packets` or `nodes`.

Rules of thumb:

- **Don't block in collectors.** No `Thread.sleep`, no blocking I/O,
  no synchronous network calls. Hand off to a worker scope.
- **Use `collectLatest` for UI mapping** when only the latest value
  matters (e.g. a "connection status" indicator).
- **If you need every packet, batch-process off-collector**:
  `client.packets.buffer(Channel.UNLIMITED).collect { … launch …  }`.
- **Watch `events` for `PacketsDropped`** in production telemetry —
  recurring drops mean your consumer is too slow.

### Single-collection flows

Some internal/transport flows are **single-collection**: a second
collector throws `IllegalStateException`. These are not exposed
directly through `RadioClient` (the engine wraps them), but if you
work below the engine — for example, writing a custom transport
driver or instrumenting `BleTransport.frames()` directly in a test —
respect the contract.

`RadioClient.packets`, `nodes`, `events`, `connection`, and `ownNode`
are all **multi-collectable** (SharedFlow / StateFlow). Collect from
as many places as you like.

### Avoiding feedback loops

When collecting `events`, **do not synchronously call back into
`RadioClient` for an action that itself emits an event of the type
you are collecting.** For example, reacting to `MeshEvent.IdentityRebound`
by sending a packet is fine; reacting to `MeshEvent.PacketsDropped`
by sending another packet that may also drop creates a tight loop.
Either gate with backoff or hand off to a separate scope.

### Deterministic tests

The `:testing` module ships:

- **`FakeRadioTransport`** — a scriptable transport for end-to-end
  engine tests; pair with `runTest`.
- **`TestClock`** — a controllable `kotlinx.datetime.Clock` for tests
  that exercise timeouts, heartbeats, or session-passkey TTL.
- **`runTest { advanceTimeBy(…) }`** drives the engine's tick-based
  watchdogs (handshake timeout, sliding Stage 2 progress timer,
  per-send ACK timeout). Choose `tickMs` smaller than the timeout you
  are exercising — see `P1EngineHardeningTest` for examples.

A typical pattern:

```kotlin
@Test
fun mySendTimesOut() = runTest {
    val transport = FakeRadioTransport(nodeNum = 0x1234)
    val client = RadioClient.Builder()
        .transport(transport)
        .clock(TestClock(this))
        .sendTimeout(5.seconds)
        .build()

    client.connect()
    val handle = client.send(packet)
    advanceTimeBy(6.seconds)
    runCurrent()

    assertEquals(SendState.Failed(SendFailure.AckTimeout), handle.state.value)
}
```

### Cross-platform notes

- **JVM / Android**: `Dispatchers.Main` requires `kotlinx-coroutines-android`
  in your app (transitively pulled in by the SDK). The engine itself
  does not depend on it.
- **iOS** (`iosArm64`, `iosX64`, `iosSimulatorArm64`): the engine runs
  on `Dispatchers.Default.limitedParallelism(1)`. Kotlin/Native's
  `freeze` rules do not apply — the SDK targets the new memory model.
- **JS / Wasm**: not currently supported. See [`roadmap.md`](roadmap.md).

### See also

- [`consumer-guides/error-handling.md`](consumer-guides/error-handling.md)
  — how to interpret `SendFailure` and `MeshEvent` failure variants
- [`consumer-guides/reactive-lifecycle-management.md`](consumer-guides/reactive-lifecycle-management.md)
  — Android lifecycle integration patterns
- [`consumer-guides/mvvm-integration.md`](consumer-guides/mvvm-integration.md)
  — ViewModel + StateFlow mapping
- [`error-taxonomy.md`](error-taxonomy.md) — canonical error catalog
