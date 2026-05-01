# Handshake FSM

> Visual companion to [`../protocol.md`](../protocol.md) §6 and [`../decisions/002-architecture.md`](../decisions/002-architecture.md). Owned by [`MeshEngine`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/internal/MeshEngine.kt); the stage enum (`HandshakeStage`) lives in [`EngineMessage.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/internal/EngineMessage.kt).

## States

The internal `HandshakeStage` enum has six values; the public `ConnectionState.Configuring` projection uses three `ConfigPhase` values (`Stage1`, `Settling`, `Stage2`).

| `HandshakeStage` | Meaning | Public projection |
|---|---|---|
| `Idle` | No connection attempted, or post-disconnect | `Disconnected` |
| `Stage1Draining` | `want_config_id = SPECIAL_NONCE_ONLY_CONFIG (69420)` posted; receiving the Stage 1 response stream (configs, channels, file_info, **no** nodes) | `Configuring(ConfigPhase.Stage1, p)` |
| `Stage1Settling` | `config_complete_id == 69420` received; settle window elapsing before the inter-stage heartbeat | `Configuring(ConfigPhase.Settling, 0.5f)` |
| `Stage2Draining` | Inter-stage `Heartbeat` flushed and `want_config_id = SPECIAL_NONCE_ONLY_NODES (69421)` posted; receiving Stage 2 (`OWN_NODEINFO` then `OTHER_NODEINFOS`; `FILEMANIFEST` short-circuits to empty) | `Configuring(ConfigPhase.Stage2, p)` |
| `SeedingSession` | Issuing `get_owner_request` to seed `session_passkey` for subsequent admin ops | `Configuring(ConfigPhase.Stage2, 0.95f)` |
| `Ready` | All flows live; heartbeat scheduler started | `Connected` |

Failure causes (`MeshtasticException.HandshakeTimeout`, transport drop, etc.) reset `HandshakeStage` to `Idle`; the engine then projects either `Reconnecting(cause, attempt)` or `Disconnected`. `progress` is monotonically non-decreasing within a single connect attempt, derived from `(received_count / expected_count)` per `protocol.md` §6 envelope counts.

> Note: prior revisions of this doc listed standalone `TransportConnecting`, `Stage1Sending`, `Stage1Settled`, `InterStageHeartbeat`, `Stage2Sending`, and `Failed` states. Those are conceptual milestones inside `MeshEngine` (you can grep `MeshEngine.kt` for `connectionState.value = ConnectionState.Configuring(...)`) — they are not separate `HandshakeStage` enum values.

## Transitions

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Stage1Draining: connect() →\nTransportState.Connected →\nToRadio(want_config_id=69420) flushed
    Idle --> Idle: TransportState.Error\n(non-recoverable, surfaces as exception)

    Stage1Draining --> Stage1Draining: FromRadio (config / channel /\nfile_info envelopes)
    Stage1Draining --> Stage1Settling: FromRadio.config_complete_id=69420
    Stage1Draining --> Idle: HandshakeTimeout(Stage1Draining) /\ntransport drop

    Stage1Settling --> Stage2Draining: settle window elapsed →\nHeartbeat(nonce=++n) flushed →\nToRadio(want_config_id=69421) flushed
    Stage1Settling --> Idle: HandshakeTimeout(Stage1Settling) /\ntransport drop

    Stage2Draining --> Stage2Draining: FromRadio.node_info\n(own then peers)
    Stage2Draining --> SeedingSession: FromRadio.config_complete_id=69421
    Stage2Draining --> Idle: HandshakeTimeout(Stage2Draining) /\ntransport drop

    SeedingSession --> Ready: AdminMessage.get_owner_response\n(session_passkey latched)
    SeedingSession --> Idle: HandshakeTimeout(SeedingSession) /\ntransport drop

    Ready --> Idle: disconnect() /\ntransport drop
    Ready --> [*]
```

### Pre-handshake byte discipline (per protocol.md §6)

While in `TransportConnecting` and during the gap between `transport.connect()` returning Connected and `Stage1Sending` posting the first byte, **the engine drops every inbound frame**. Stale `FromRadio` from a prior PhoneAPI session can otherwise corrupt the early state. Once Stage 1's nonce is on the wire, every inbound frame is dispatched.

### Heartbeat in handshake

A heartbeat with `nonce = ++counter` is sent **once** between Stage 1 settle and Stage 2 send. It serves two purposes:

1. Defeats the firmware's per-connection memcmp dedup, ensuring the device treats the upcoming Stage 2 want_config as a fresh request even if the bytes happen to match a recent send.
2. Probes the transport's write path before the larger Stage 2 dump.

The 30 s scheduled heartbeat (`protocol.md` §16) starts only on entry to `Ready`.

## Failure transitions and reconnect

`Failed(cause)` always returns to `Idle`. The engine then runs a fixed reconnect policy: **exponential backoff (initial 500 ms, factor 2, capped at 60 s, with ±20 % jitter), infinite attempts** while the consumer holds the `RadioClient`. Each retry begins a new attempt with `Connecting(attempt + 1)`. The policy is intentionally not configurable in 0.x — Phase 5 may revisit and add `Builder.reconnect(...)` if real-world adopters report concrete needs (recorded as a separate ADR if/when it lands).

Causes that DO NOT auto-reconnect:
- `MeshtasticException.FirmwareTooOld` (firmware build below `MIN_SUPPORTED_FIRMWARE`, surfaced once `metadata` envelope arrives).

Causes that DO auto-reconnect:
- `MeshtasticException.Transport(...)`
- `MeshtasticException.HandshakeTimeout(stage)`
- `MeshtasticException.StorageUnavailable(...)` (single retry; second consecutive failure surfaces and stops)

## Related

- `protocol.md` §6 — wire-level envelope sequence, including the firmware nonce specials (69420/69421).
- ADR-002 — actor model owning this FSM.
- ADR-005 — `ConnectionState` (no `DeviceSleep`); `Reconnecting` carries `cause`.
- `references/meshtastic-android-protocol-notes.md` — 100 ms inter-stage delay precedent.
- `references/meshtastic-apple-protocol-notes.md` — `requiresPeriodicHeartbeat` per-transport.
