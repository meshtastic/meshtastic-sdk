# Transport isolation — module structure and architecture

> Reference: [`../decisions/002-architecture.md`](../decisions/002-architecture.md) (single-writer actor over pluggable transport), [`../decisions/006-multi-module-rationale.md`](../decisions/006-multi-module-rationale.md) (module boundary rationale), [`./module-graph.md`](./module-graph.md) (dependency graph), [`../SPEC.md`](../SPEC.md) §3.4 (RadioTransport interface).

## Why transports are separate modules

The `RadioTransport` interface is defined in `:core`, but concrete transport implementations (`BLE`, `TCP`, `Serial`) live in separate modules (`:transport-ble`, `:transport-tcp`, `:transport-serial`) rather than as subpackages of `:core`.

### Hard architectural boundary

| Aspect | Policy |
|---|---|
| **Interface** | Defined in `:core` (`org.meshtastic.sdk.RadioTransport`, `Frame`, `TransportState`, `TransportIdentity`). All transports implement this contract. |
| **Implementation** | Live in separate modules. `:core` has **zero** implementation dependencies on any transport module. |
| **Wiring** | Transports are plugged in via `Builder.transport(...)` at `RadioClient` construction time. The engine accepts any `RadioTransport` and never knows which one it is. |
| **Enforcement** | Gradle rule (`:core:verifyModuleBoundary`) + detekt fail the build if `:core` imports anything from `:transport-*`. |

### Benefits of separation

1. **Zero runtime footprint for unused transports.** An app that needs only TCP doesn't pull BLE or serial libraries into its classpath.

2. **Independent versioning and release cycle.** A bug fix in `:transport-ble` doesn't require all consumers to update `:core`. Consumers pull only the transports they need.

3. **Pluggable at build time.** Custom transport implementations (e.g., `transport-mqtt-proxy`, or a proprietary `transport-lora`) can be swapped in without modifying `:core`.

4. **Cleaner dependency trees.** `:core` has exactly two dependencies: `:proto` (wire types) and `kotlinx-coroutines` (actor concurrency). Each transport brings only its own heavy lifting (Kable for BLE, Ktor for TCP, jSerialComm/usb-serial for serial).

## Per-transport implementation patterns

### Platform-specific targets

| Module | android | jvm | ios | Notes |
|---|:---:|:---:|:---:|---|
| `:transport-ble` | ✓ | ✓ (macOS only) | ✓ | Single Kable backend for all platforms. |
| `:transport-tcp` | ✓ | ✓ | ✓ | Ktor sockets work on all JVM + native targets. |
| `:transport-serial` | ✓ | ✓ | — | Android: usb-serial-for-android via USB OTG. JVM: jSerialComm. Unified via `expect/actual`. iOS: no serial access without MFi accessory. |

### Error handling differences

Each transport has different failure modes. The engine (via `EngineMessage.TransportStateChanged`) observes all of them uniformly:

| Transport | Recoverable failures | Terminal | Notes |
|---|---|---|---|
| **BLE (Kable)** | Disconnect, re-negotiate GATT. | GATT mismatch, bonding failure, peer rejection. | Requires re-advertise after terminal failure. |
| **TCP (Ktor sockets)** | Timeout, connection reset → can reconnect to same IP:port. | Peer refused, DNS failure on first connect. | Idempotent `connect()` allows retry loop. |
| **Serial (jSerialComm/usb-serial)** | Port busy (held by another process). | USB device unplugged, permission denied, unsupported baud rate. | Android USB OTG requires `UsbManager.requestPermission(...)` on first access. |

All emit `TransportState.Error(recoverable: Boolean)` to the engine inbox. The engine decides whether to `disconnect()` immediately (terminal) or retry.

## Inter-module communication

### From engine to transport

1. **Initialization** (before `connect()`):
   - `Builder.transport(RadioTransport)` injects a transport instance.
   - Engine keeps a reference: `private val transport: RadioTransport`.

2. **At connect**:
   - Engine calls `transport.connect()` (suspend). Blocks until the transport reaches `TransportState.Connected` or throws.
   - Concurrently, the frame reader coroutine waits for `transport.state` to emit `Connected` before calling `transport.frames()`.

3. **During steady state**:
   - Frame reader collects `transport.frames()` (a `Flow<Frame>`); each frame posts as `EngineMessage.FrameRx` to the inbox.
   - Transport state observer collects `transport.state` (a `StateFlow<TransportState>`); each change posts as `EngineMessage.TransportStateChanged`.
   - Outbound writer drains `outbound: Channel<Frame>` and calls `transport.send(frame)` sequentially.

4. **On disconnect**:
   - Engine's `finally` block calls `transport.disconnect()`.
   - SupervisorJob cancellation propagates; frame reader and outbound writer exit.

### Ownership and cleanup

- The **engine owns** the `RadioTransport` instance and is responsible for `disconnect()` on shutdown.
- The **transport owns** any platform-specific resources (BLE GATT connection, TCP socket, serial port file descriptor).
- On `RadioClient.disconnect()`:
  1. SupervisorJob is cancelled.
  2. Frame reader and outbound writer exit (CancellationException caught and re-thrown).
  3. Engine's `finally` calls `transport.disconnect()` (must not throw).
  4. Storage is flushed and closed.

## Adding a new transport

1. Create a new module `:transport-newname` in the root.
2. Depend on `:core` (for `RadioTransport`, `Frame`, `TransportState`, `TransportIdentity`) and `:proto` (for wire types).
3. Implement `RadioTransport`:
   ```kotlin
   class NewTransport : RadioTransport {
       override val state: StateFlow<TransportState> = /* observe your platform's connection state */
       override val identity: TransportIdentity = /* e.g., url, port, device ID */
       override suspend fun connect() { /* open your resource */ }
       override suspend fun disconnect() { /* release your resource */ }
       override fun frames(): Flow<Frame> { /* collect inbound frames */ }
       override suspend fun send(frame: Frame) { /* write to wire */ }
   }
   ```
4. Update `module-graph.md` and `docs/decisions/003-tooling.md` with the new transport and its rationale.
5. Add a sample in `samples/cli` or a new sample app showing how to wire it.
6. (Future) If cross-platform: use `expect/actual` for platform-specific resources (e.g., `:transport-serial` for Android USB vs JVM serial).

## Related

- ADR-002 — engine architecture and why `RadioTransport` is the boundary.
- ADR-006 — multi-module rationale (why we split modules instead of subpackages).
- SPEC §3.4 — `RadioTransport` interface specification.
- `module-graph.md` — visual dependency graph.
