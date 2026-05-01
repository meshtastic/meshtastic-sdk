# Finite State Machine (FSM) patterns and conventions

> Guide to the FSM patterns used in the Meshtastic SDK engine and how to extend them correctly.

## Overview

The Meshtastic SDK uses finite state machines (FSMs) in three critical layers:

1. **HandshakeMachine** — the main PhoneAPI handshake (§6 of protocol.md)
2. **ConnectionState** — public projection of engine state to consumers
3. **TransportState** — internal transport-layer state transitions

This document describes the pattern, rationale, and how to avoid duplication when adding new FSMs.

## The handshake FSM (canonical example)

Implemented inline in [`MeshEngine.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/internal/MeshEngine.kt) with the stage enum (`HandshakeStage`) defined in [`EngineMessage.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/internal/EngineMessage.kt). Documented in `docs/architecture/handshake-fsm.md`.

### States and transitions

```
States:     Idle → TransportConnecting → Stage1Sending → Stage1Draining → Stage1Settled
              ↑                                              ↓
         Idle ← Failed                      InterStageHeartbeat ↓
                                                  ↓
              Stage2Sending ← Stage2Draining ← SeedingSession → Ready
                    ↓                              ↑
                 Failed ←──────────────────────────┘
```

### Implementation structure

Each state is a value of the internal enum:

```kotlin
internal enum class HandshakeStage {
    Idle,
    Stage1Draining,
    Stage1Settling,
    Stage2Draining,
    SeedingSession,
    Ready,
}
```

> **Note:** The implementation below is a *conceptual illustration* of the FSM pattern. In practice, the handshake logic is implemented directly in `MeshEngine`'s `when`-matching over `EngineMessage` variants rather than as a standalone pure `transition()` function. The pattern principles (explicit side effects, exhaustive event handling) still apply, but the code structure is the single-writer actor loop, not a separate state machine class.

Transitions are conceptually a state machine:

```kotlin
fun transition(
    state: HandshakeState,
    event: HandshakeEvent
): Pair<HandshakeState, List<SideEffect>> {
    return when (state) {
        is HandshakeState.Idle -> {
            when (event) {
                is HandshakeEvent.Connect -> {
                    TransportConnecting(attempt = 1) to listOf(
                        SideEffect.ConnectTransport
                    )
                }
                else -> state to emptyList()
            }
        }
        is HandshakeState.TransportConnecting -> {
            when (event) {
                is HandshakeEvent.TransportConnected -> {
                    Stage1Sending to listOf(
                        SideEffect.SendConfigRequest(SPECIAL_NONCE_ONLY_CONFIG)
                    )
                }
                is HandshakeEvent.TransportFailed -> {
                    Failed(event.cause) to listOf()
                }
                else -> state to emptyList()
            }
        }
        // ... other states
    }
}
```

### Key principles

1. **Pure function** — `transition()` is deterministic and has no side effects. Given the same `(state, event)` pair, it always returns the same result.
2. **Explicit side effects** — Side effects (sending frames, starting timers, emitting state to flows) are collected in a `List<SideEffect>` and executed by the engine actor *after* state update.
3. **Exhaustive event handling** — The `when` expression over events is exhaustive within the state. Unhandled events silently stay in the same state (no-op).
4. **Timeout as an event** — Timeouts are modeled as events (`HandshakeEvent.StageTimeout`), not as implicit delays. This makes testing deterministic.

## State projection (ConnectionState)

The handshake FSM is internal (`HandshakeState`). The engine projects it to a public `ConnectionState` for consumer observability:

```kotlin
public sealed interface ConnectionState {
    object Disconnected : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState
    object Connected : ConnectionState
    data class Reconnecting(val cause: Throwable, val attempt: Int) : ConnectionState
}
```

Projection is a deterministic function:

```kotlin
fun HandshakeState.toConnectionState(): ConnectionState = when (this) {
    is Idle -> Disconnected
    is TransportConnecting -> Connecting(attempt = attempt)
    is Stage1Sending -> Configuring("STAGE_1_REQUESTED", 0f)
    // ... etc
}
```

The benefits:

- **Abstraction** — consumers never see `HandshakeState`'s internal complexity.
- **Stability** — adding a new internal `HandshakeState` doesn't break consumer code if the public `ConnectionState` doesn't change.
- **Versioning** — `ConnectionState` is part of the public API and protected by `checkKotlinAbi`.

## Transport state machine

Transport layers (BLE, Serial, TCP) each implement a simple state machine:

```kotlin
sealed interface TransportState {
    object Idle : TransportState
    object Connecting : TransportState
    object Connected : TransportState
    data class Error(val reason: String, val recoverable: Boolean) : TransportState
}
```

This is simpler than the handshake FSM because:

- Transport only cares about connection (yes/no) and errors.
- Handshake complexity (stages, config) is handled by `HandshakeMachine`, not the transport.

## Testing FSMs: the table-driven pattern

Use parameterized (table-driven) tests to verify every state + event combination:

```kotlin
@Test
fun testHandshakeTransitions() {
    val testCases = listOf(
        // (state, event) -> expectedNextState, expectedSideEffects
        Idle to Connect(1)
            -> TransportConnecting(1) with listOf(SideEffect.ConnectTransport),

        TransportConnecting(1) to TransportConnected
            -> Stage1Sending with listOf(SideEffect.SendConfigRequest(69420)),

        TransportConnecting(1) to TransportFailed("GATT error")
            -> Failed("GATT error") with listOf(),

        Stage1Draining to ConfigComplete(69420)
            -> Stage1Settled with listOf(SideEffect.StartSettleTimer(100.ms)),

        // ... dozens more cases covering all paths
    )

    testCases.forEach { (state, event, expectedNext, expectedEffects) ->
        val (nextState, effects) = transition(state, event)
        assertEquals(expectedNext, nextState)
        assertEquals(expectedEffects, effects)
    }
}
```

This pattern ensures:

- **Completeness** — every documented state transition has a test.
- **Regressions** — adding a new transition inadvertently cannot silently change existing ones.
- **Clarity** — the table is a specification; readers see all transitions at a glance.

See `core/src/commonTest/kotlin/org/meshtastic/sdk/HandshakeFsmTest.kt` for the canonical implementation.

## Adding a new FSM: checklist

If you need to add a new FSM (e.g., a "SeedingSessionFSM" or "AdminRpcFSM"):

### 1. Define states and events

```kotlin
sealed class MyFsmState {
    object Idle : MyFsmState()
    object Working : MyFsmState()
    data class Failed(val cause: Throwable) : MyFsmState()
}

sealed class MyFsmEvent {
    object Start : MyFsmEvent()
    data class Complete(val result: String) : MyFsmEvent()
    data class Error(val cause: Throwable) : MyFsmEvent()
}
```

### 2. Define side effects

```kotlin
sealed class MyFsmEffect {
    object DoWork : MyFsmEffect()
    data class EmitResult(val value: String) : MyFsmEffect()
}
```

### 3. Implement the pure transition function

```kotlin
fun transition(state: MyFsmState, event: MyFsmEvent): Pair<MyFsmState, List<MyFsmEffect>> {
    return when (state) {
        is MyFsmState.Idle -> when (event) {
            is MyFsmEvent.Start -> MyFsmState.Working to listOf(MyFsmEffect.DoWork)
            else -> state to emptyList()
        }
        is MyFsmState.Working -> when (event) {
            is MyFsmEvent.Complete -> MyFsmState.Idle to listOf(MyFsmEffect.EmitResult(event.result))
            is MyFsmEvent.Error -> MyFsmState.Failed(event.cause) to emptyList()
            else -> state to emptyList()
        }
        // ... etc
    }
}
```

### 4. Write table-driven tests

```kotlin
@Test
fun testMyFsmTransitions() {
    val testCases = listOf(
        // state, event -> expectedState, expectedEffects
        Idle to Start -> Working to listOf(DoWork),
        Working to Complete("ok") -> Idle to listOf(EmitResult("ok")),
        Working to Error(cause) -> Failed(cause) to emptyList(),
    )
    testCases.forEach { ... }
}
```

### 5. Document in docs/architecture/

Create a diagram (Mermaid or ASCII) showing the state graph and document each transition's rationale.

### 6. Integrate into the engine actor

Have the engine actor call `transition()` and apply side effects:

```kotlin
private suspend fun handleFsmEvent(event: MyFsmEvent) {
    val (nextState, effects) = transition(fsmState, event)
    fsmState = nextState
    effects.forEach { effect ->
        when (effect) {
            is MyFsmEffect.DoWork -> doWork()
            is MyFsmEffect.EmitResult -> emit(event = MyFsmEvent.Result(effect.value))
        }
    }
}
```

## Avoiding duplication

### Anti-pattern: inline state checks

❌ **Don't** do this:

```kotlin
if (handshakeState is Stage1Draining) {
    when (envelope) {
        is FromRadio.Config -> { /* ... */ }
        is FromRadio.ConfigComplete -> { /* transition to Stage1Settled */ }
    }
}
if (handshakeState is Stage2Draining) {
    when (envelope) {
        is FromRadio.NodeInfo -> { /* ... */ }
        is FromRadio.ConfigComplete -> { /* transition to SeedingSession */ }
    }
}
```

This scatters state logic across handlers and is error-prone.

### Correct pattern: centralized transition

✅ **Do** this:

```kotlin
private suspend fun handleEnvelope(envelope: FromRadio) {
    val event = when (envelope) {
        is FromRadio.Config -> HandshakeEvent.ConfigReceived(envelope.config)
        is FromRadio.ConfigComplete -> HandshakeEvent.ConfigComplete(envelope.config_complete_id)
        // ... etc
    }
    val (nextState, effects) = transition(handshakeState, event)
    // Apply state and effects
}
```

The transition logic is centralized in one `transition()` function.

### Anti-pattern: multiple boolean flags

❌ **Don't** do this:

```kotlin
var inStage1 = false
var inStage2 = false
var inSeedingSession = false

// Scattered throughout code:
if (inStage1) { /* ... */ }
if (inStage2) { /* ... */ }
// Hard to reason about; easy to violate invariants (e.g., both flags true)
```

### Correct pattern: single state enum

✅ **Do** this:

```kotlin
sealed class HandshakeState {
    object Stage1Sending : HandshakeState()
    object Stage2Sending : HandshakeState()
    // ...
}

// Single source of truth; impossible to be in two states at once
```

## Monitoring FSM health

To detect state machine anomalies, log every transition:

```kotlin
fun transition(state: HandshakeState, event: HandshakeEvent): Pair<HandshakeState, List<SideEffect>> {
    val nextState = /* ... */
    logger.debug("HandshakeFSM: $state + $event -> $nextState")
    return nextState to effects
}
```

In production, this helps diagnose unexpected reconnects or hangs.

## Related

- [`docs/architecture/handshake-fsm.md`](./handshake-fsm.md) — Handshake FSM specification
- [`docs/architecture/engine-actor.md`](./engine-actor.md) — Engine actor design and how it uses FSMs
- [`docs/testing.md`](../testing.md) — Testing patterns
- [ADR-002: Single-writer actor concurrency](../decisions/002-architecture.md) — Why we use FSMs instead of locks
