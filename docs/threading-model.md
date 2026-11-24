# Threading model

This page summarizes which code runs where in `meshtastic-sdk`, for contributors and for consumers. The decisions live in ADRs:

- [ADR-002](decisions/002-architecture.md): the engine is a single-writer actor.
- [ADR-008](decisions/008-architecture-enforcement.md): how the actor invariant is enforced (detekt `ForbiddenImport` and Gradle).
- [ADR-012](decisions/012-transport-threading.md): the transport threading contract.

The ADRs give the reasons; this page gives the rules.

## Three layers

```text
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
│   ▸ One coroutine processes messages, one at a time.            │
│   ▸ Four atomicfu sites (supervisorJobRef, nextWireId,          │
│     connectionGeneration, activeConnectDeferred) are boundary   │
│     handles, not state.                                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Transport modules — bridge native callbacks/threads to flows    │
│   ▸ TCP : Ktor sockets, fully suspending.                       │
│   ▸ WebSocket : Ktor client session, fully suspending.          │
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
│   ▸ Default dispatcher is a fixed pool of 4 threads             │
│     (StorageDispatcher.*.kt): daemon threads on JVM/Android,    │
│     newFixedThreadPoolContext on iOS.                           │
│   ▸ See docs/architecture/storage.md, thread-safety section.    │
└─────────────────────────────────────────────────────────────────┘
```

## The actor invariant

The engine owns its state. Consumers and transports reach it by posting messages (suspend calls or flow emissions), and one coroutine processes them in order. That gives:

- No races on engine state: no coroutine ever sees a half-applied mutation.
- Deterministic tests: `runTest { … }` advances the engine one message at a time.
- No reentrancy hazards: the engine never calls a transport while holding a state lock, because there are none.

detekt enforces it in the engine's own files (`MeshEngine`, `EngineMessage`, `MeshState`, `CommandDispatcher`, `WireCodec`): the `ForbiddenImport/engine` rule in `config/detekt/detekt.yml` bans `kotlinx.coroutines.sync.{Mutex,Semaphore}` and `java.util.concurrent.locks.ReentrantLock` there. Outside the engine a `Mutex` is allowed; a transport-side one carries its `// ADR-012:` comment, which review checks. See the [`docs/architecture/enforcement.md`](architecture/enforcement.md) matrix.

## What you may write

| You're writing… | You may use | You must avoid |
|---|---|---|
| Engine code (`:core`, on the engine dispatcher) | `Channel`, `Flow`, suspend functions, plain `var`s owned by the actor | `Mutex`, `Semaphore`, `ReentrantLock`, `synchronized {}`, `AtomicReference` for state, `Thread.sleep` |
| Transport code (`:transport-*`) | Whatever the native API forces (callbacks, blocking reads on a dedicated thread, atomics for handle ownership) | Blocking the engine dispatcher; calling engine internals; sharing mutable state with the engine other than through the documented `Flow` and `suspend send` interface |
| Consumer code (your app) | Any dispatcher | Assuming the engine runs on `Main` |

## How to add a new transport

See the `transport-module-authoring` skill in [`.github/skills/`](../.github/skills/) and the existing modules (`transport-tcp/`, `transport-ble/`, `transport-serial/`, `transport-websocket/`). The contract is small: return a `Flow<Frame>` from `frames()`, implement `suspend fun send(frame: Frame)`, expose `state` and `identity`, and follow the [ADR-012](decisions/012-transport-threading.md) threading rules.

## Verifying

```shell
./gradlew detekt :core:verifyModuleBoundary
```

Both run as part of `./gradlew check`. The engine test suite runs the engine under `runTest`, which exercises the non-blocking property.

## Consumer-side threading guide

The rules in the previous sections bind contributors. This section covers what the SDK guarantees your app and which mistakes to avoid.

### Hot flows

`RadioClient` exposes these reactive surfaces. All are hot: they run whether or not anyone collects.

| Property | Type | Replay | Buffer | When the buffer is full |
|---|---|---|---|---|
| `connection` | `StateFlow<ConnectionState>` | current value | n/a | newest wins |
| `ownNode` | `StateFlow<NodeInfo?>` | current value | n/a | newest wins |
| `nodes` | `Flow<NodeChange>` | a fresh `Snapshot` per subscription | 256 | the change is dropped and `MeshEvent.PacketsDropped` is emitted |
| `packets` | `Flow<MeshPacket>` | none | 128 | the packet is dropped and `MeshEvent.PacketsDropped(Packets, n)` is emitted |
| `events` | `Flow<MeshEvent>` | none | 64 | the oldest event is dropped |
| `fromRadio` | `Flow<FromRadio>` | none | 256 | the oldest envelope is dropped |

The shared flows behind `nodes`, `packets`, `events` and `fromRadio` have `replay = 0`. A late subscriber to `packets`, `events` or `fromRadio` sees only what is emitted after it starts collecting. A subscriber to `nodes` first receives a `Snapshot` of the current node map, then deltas; `nodeSnapshot()` returns the map once.

### Dispatcher choice (consumer side)

You can collect on any dispatcher. Common patterns:

- Android UI: collect in `viewModelScope` with `repeatOnLifecycle`. The SDK never pins to `Dispatchers.Main`.
- iOS and SwiftUI: collect on `Dispatchers.Main` (the main queue). Don't block in the collector; long work belongs on `Dispatchers.Default`.
- JVM headless, CLI or server: any dispatcher. One `CoroutineScope` per `RadioClient`, cancelled together, works well.

The engine runs on `SupervisorJob() + Dispatchers.Default` unless you pass `Builder.coroutineContext(...)`. `Dispatchers.Default` is a thread pool on JVM, Android and iOS; the actor stays single-writer regardless, because its channel serializes the work. Your collector's dispatcher does not have to match.

### Lifecycle and cancellation

- `RadioClient.connect()` suspends until `ConnectionState.Connected`. Cancelling the caller's scope after `connect` returns does not disconnect; call `client.disconnect()`, which is idempotent and suspends until cleanup completes. There is deliberately no blocking `close()` or `AutoCloseable`, because a blocking bridge invites an ANR on Android's main thread; tear down from a coroutine, for example `try { … } finally { client.disconnect() }`.
- A disconnected `RadioClient` can be connected again with `connect()`.
- The engine owns the heartbeat. Cancelling a flow collection does not stop it; only `disconnect()` does.
- `connect()` is cancellable, and a cancelled connect is fully unwound, including any transport handshake in flight. The client is then `Disconnected`.

### Backpressure and dropped events

The engine never blocks on consumer collection. When a collector is slow its buffer fills, and new `packets` or `nodes` items are dropped with a `MeshEvent.PacketsDropped(flow, count)` on `events`.

Rules of thumb:

- Don't block in collectors: no `Thread.sleep`, blocking I/O or synchronous network calls. Hand work to another scope.
- Use `collectLatest` for UI mapping when only the latest value matters, such as a connection indicator.
- If you need every packet, move processing out of the collector: `client.packets.buffer(Channel.UNLIMITED).collect { … }`.
- Watch `events` for `PacketsDropped` in production telemetry; recurring drops mean the consumer is too slow.

### Single-collection flows

Some transport flows are single-collection: a second collector throws `IllegalStateException`. `RadioClient` does not expose them (the engine wraps them), but code below the engine, such as a custom transport or a test that collects `BleTransport.frames()` directly, has to respect that.

`RadioClient.packets`, `nodes`, `events`, `fromRadio`, `connection` and `ownNode` are all multi-collectable. Collect from as many places as you like.

### Avoiding feedback loops

When collecting `events`, don't synchronously call back into `RadioClient` for an action that emits the event you are collecting. Reacting to `MeshEvent.IdentityRebound` by sending a packet is fine; reacting to `MeshEvent.PacketsDropped` by sending a packet that may also drop creates a tight loop. Gate it with backoff or hand it to a separate scope.

### Deterministic tests

The `:testing` module provides:

- `FakeRadioTransport`, a scriptable transport for end-to-end engine tests; with `autoHandshake = true` it answers the handshake itself.
- `TestClock`, a controllable `kotlin.time.Clock` for tests of timeouts, heartbeats and admin session expiry.

`runTest { advanceTimeBy(…) }` drives the engine's timers (handshake timeouts, the sliding Stage 2 timer, the per-send ACK timeout) once the engine runs on the test scheduler through `Builder.coroutineContext(backgroundScope.coroutineContext)`. `P1EngineHardeningTest` has examples.

A typical pattern, where `packet` is a unicast `want_ack` packet:

```kotlin
@Test
fun mySendTimesOut() = runTest {
    val transport = FakeRadioTransport(
        identity = TransportIdentity("fake:send-timeout"),
        autoHandshake = true,
    )
    val client = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .clock(TestClock())
        .sendAckTimeout(5.seconds)
        .build()

    client.connect()
    val handle = client.send(packet)
    advanceTimeBy(6.seconds)
    runCurrent()

    assertEquals(SendState.Failed(SendFailure.AckTimeout), handle.state.value)
}
```

### Cross-platform notes

- JVM and Android: `Dispatchers.Main` needs `kotlinx-coroutines-android` in your app (the SDK pulls it in transitively). The engine itself does not use it.
- iOS (`iosArm64`, `iosX64`, `iosSimulatorArm64`): the engine runs on `Dispatchers.Default`. Kotlin/Native's old `freeze` rules do not apply; the SDK targets the new memory model.
- JS and Wasm (`js`, `wasmJs`): one thread. `Dispatchers.Default` is the host's event loop, so the engine and the host's UI share it.

### See also

- [`consumer-guides/error-handling.md`](consumer-guides/error-handling.md): how to read `SendFailure` and the `MeshEvent` failure variants
- [`error-taxonomy.md`](error-taxonomy.md): the error catalog
