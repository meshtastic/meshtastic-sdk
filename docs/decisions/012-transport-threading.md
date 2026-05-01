# ADR-012: Transport-side threading and blocking-IO contract

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-04-19 |
| **Deciders** | Maintainers |
| **Supersedes** | — |
| **Related** | [ADR-002](002-architecture.md), [ADR-008](008-architecture-enforcement.md), [ADR-010](010-jserialcomm-jvm-serial.md) |

## Context

[ADR-002](002-architecture.md) makes the engine a single-writer actor: no
mutexes, no semaphores, no `synchronized {}` blocks anywhere on its hot
path. Transports, however, are *not* actors — they wrap libraries with
fundamentally different threading models:

- **TCP** — Ktor sockets, fully suspending; no threading concerns.
- **BLE** — Kable on JVM/Android, CoreBluetooth on iOS. Callback-driven; we
  bridge into coroutines.
- **Serial** — `jSerialComm` on both JVM and Android (blocking reads on
  a dedicated thread; Android opens via `SerialPort.fromAndroidPort` after
  the host app handles the `UsbManager` permission Intent).

The audit asked: what's the contract between a transport's threading
model and the engine's actor invariant?

## Decision

A transport module MUST expose a `Flow<ByteString>` for inbound bytes and a
`suspend fun send(ByteString)` for outbound bytes. *How* the transport
obtains those bytes is its own business, with two rules:

1. **No transport may block the engine's coroutine context.** Any blocking
   I/O (e.g., jSerialComm reads) runs on a dedicated background thread or
   a `Dispatchers.IO`-confined coroutine. The engine consumes the
   resulting `Flow` from its own dispatcher.
2. **The engine sees serial events only.** Transports that aggregate
   callbacks into a flow MUST funnel through a single `Channel` (or
   equivalent `MutableSharedFlow` with `BUFFERED`/`SUSPEND` policy) so
   that ordering is preserved and back-pressure is honored.

Inside a transport module, mutexes / atomics are permitted *only* for:
1. ownership of native handles (e.g., the Kable `Peripheral`, the
   `SerialPort`); and
2. lifecycle / idempotency flags that protect connect-and-shutdown
   sequencing without crossing into the engine actor — for example
   `BleTransport`'s `atomic(Boolean)` `shuttingDown` / `framesCollected`
   guards used to make `disconnect()` idempotent and to prevent late
   inbound frames from being processed after teardown.

They MUST NOT appear in code that runs on the engine dispatcher. The
detekt `ForbiddenImport` rules added by
[ADR-008](008-architecture-enforcement.md) are scoped to forbid
`kotlinx.coroutines.sync.*` and `java.util.concurrent.locks.ReentrantLock`
across all modules, with a documented carve-out at the call site — every
transport-internal lock or atomic MUST carry an inline
`// ADR-012: native-handle ownership` (or `// ADR-012: lifecycle
idempotency`) comment so reviewers can see the justification next to the
declaration.

## Rationale

- Keeps the engine's invariant intact while allowing transports to use
  whatever native API actually exists.
- Makes the contract testable: a fake transport in `:testing` simply
  exposes a `Flow` and a `suspend send`, no thread acrobatics needed.
- Documents *why* the few atomics in transport code are not violations of
  ADR-002 — they're not on the engine path.

## Alternatives considered

| Option | Why not |
|---|---|
| Force every transport to be fully suspending | Impossible for jSerialComm without writing our own JNI; CoreBluetooth's callback model also resists it. |
| Move the actor model into the transports too | Quadruples the surface area of the actor invariant; cost vastly outweighs benefit. |
| Allow ad-hoc threading per transport | The audit found drift between the transport implementations precisely because there was no written contract. |

## Consequences

### Positive
- The actor invariant on `:core` survives untouched.
- New transport authors have a one-page contract: emit a `Flow`, accept a
  `suspend send`, never block the engine dispatcher.
- The detekt `ForbiddenImport` carve-outs are visible in code review —
  every `Mutex` or `ReentrantLock` use is a flagged exception with an
  ADR reference.

### Negative / costs
- jSerialComm forces one thread per active port (acceptable; we don't
  expect dozens of concurrent serial connections).
- Transport authors must understand both Kotlin coroutines and the
  underlying native callback model. Documented in the
  [`transport-module-authoring` skill](../../.github/skills/transport-module-authoring/SKILL.md).

### Follow-ups
- [ ] Add a `transport-contract` test in `:testing` that asserts the
      "no blocking on engine dispatcher" rule by running the engine on a
      single-thread dispatcher and detecting starvation.
- [ ] Cross-link this ADR from the transport-module-authoring skill so
      new transport contributors land here.

## Updates

- **2026-04-21:** `BleTransport` gained an optional third constructor
  parameter `parentContext: CoroutineContext = EmptyCoroutineContext`.
  The internal scope is now
  `CoroutineScope(SupervisorJob(parent = parentContext[Job]) + parentContext.minusKey(Job) + Dispatchers.Default)`,
  so cancelling the caller's `Job` cleanly cancels the transport. This
  is additive — existing call sites that pass nothing retain the old
  behaviour. Other transports (TCP, serial) already accepted a
  `CoroutineScope`/parent; this aligns BLE with the same pattern.

## References

- [ADR-002](002-architecture.md) — engine actor invariant.
- [ADR-008](008-architecture-enforcement.md) — detekt rules that gate
  this contract.
- [`docs/architecture/handshake-fsm.md`](../architecture/handshake-fsm.md)
