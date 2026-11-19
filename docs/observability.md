# Observability

How to see what `meshtastic-sdk` is doing in production and in tests.
This document covers logging, frame-level diagnostics, metrics, and the
event surface that the engine exposes.

The architectural decisions behind the logging surface live in
[ADR-011](decisions/011-logsink.md). This document is the user-facing
guide.

## Logging

The SDK ships **one** logging hook: `LogSink`. It's a `fun interface` in
[`org.meshtastic.sdk.Logging`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Logging.kt).

```kotlin
val client = RadioClient.Builder()
    .transport(tcp)
    .logger { level, tag, message, throwable ->
        myAppLogger.log(level.toMyLevel(), "$tag/$message", throwable)
    }
    .build()
```

If you don't supply a `LogSink`, the SDK is silent. This is deliberate —
a credential-bearing library should not write to stderr by default.

### Levels

`LogLevel` has six values: `NONE`, `VERBOSE`, `DEBUG`, `INFO`, `WARN`, `ERROR`.
`NONE` is reserved as the off-switch for the protocol logger
(see below). The SDK emits at `DEBUG` for connection lifecycle and
handshake stage transitions; `INFO` for terminal state changes; `WARN`
for recoverable protocol anomalies; `ERROR` for unrecoverable conditions
reported to the caller via `MeshtasticException`.

| Level | Used for |
|-------|----------|
| `VERBOSE` | Per-packet traces (inbound Rx, heartbeat ticks) — hot path, high volume |
| `DEBUG` | Lifecycle transitions, RPC dispatch, send queueing, ACK timers |
| `INFO` | Session Ready, handshake stage starts/completes, connect/disconnect |
| `WARN` | Recoverable anomalies — decode failures, transport errors, drops, identity rebinds |
| `ERROR` | Handshake timeout, protocol violations that terminate the session |

### Tags

Each internal component emits a consistent `tag` string:

| Tag | Source |
|-----|--------|
| `MeshEngine` | Engine actor — lifecycle, handshake, routing, sends |
| `CommandDispatcher` | Phase 2 RPC registry — register/complete/timeout |
| `SerialTransport` | jSerialComm transport — connect/disconnect, frame drops |

Transport modules that don't yet carry a `LogSink` parameter (BLE, TCP)
surface diagnostics through exceptions only. Future phases will thread
the logger.

### Performance

Internally the SDK uses `@PublishedApi internal inline` extension
functions on `LogSink` (`verbose`, `debug`, `info`, `warn`, `error`).
These take a lambda for the message parameter:

```kotlin
logger.info(TAG) { "Session Ready — myNodeNum=0x${myNodeNum.toString(16)}" }
```

When the sink is `LogSink.Silent` (the default), the lambda is **never
invoked** — no string concatenation, no `toString()`, no allocation.
This means the SDK has zero observable overhead from logging in the
common case. Only when a host supplies a real `LogSink` does the message
construction run.

### Sample bindings

`samples/cli` ships a Kermit-backed `LogSink` you can copy. See
[`samples/cli/src/main/kotlin/org/meshtastic/cli/internal/`](../samples/cli/src/main/kotlin/org/meshtastic/cli/internal/).

## Protocol-level logging

Wire-byte / frame-level dumps are a **separate**, opt-in hook
controlled by `Builder.protocolLogging(level, redactor)`. `LogLevel.NONE`
is the default. Enabling this hook emits every encoded `FromRadio` /
`ToRadio` envelope at the specified level — including pre-shared keys,
session passkeys, and node positions unless a redactor strips them.

```kotlin
val client = RadioClient.Builder()
    .transport(tcp)
    .logger(myLogSink)
    .protocolLogging(
        level = LogLevel.DEBUG,            // ⚠ raw bytes — dev only
        redactor = PayloadRedactor.Default // masks PSKs/passkeys/positions
    )
    .build()
```

`PayloadRedactor.Default` is on unless explicitly overridden; pass
`PayloadRedactor.None` to disable redaction. Even with redaction,
**do not enable protocol logging in a production build** — the
encoded envelopes still reveal mesh structure and timing.

## Engine event surface

`RadioClient.events: Flow<MeshEvent>` is the canonical place to observe
state changes — connection lifecycle, handshake progress, identity
rebinds, and protocol warnings. Consumers building dashboards, metrics,
or status indicators should subscribe to this flow rather than parse log
lines.

`MeshEvent` is a sealed interface. Current variants:

- `QueueStatusChanged(status)` — firmware queue update.
- `Notification(notification)` — `ClientNotification` from device.
- `TransportError(error)` — recoverable transport failure mid-session.
- `ProtocolWarning(message)` — protocol-level anomaly (e.g. identity rebind).
- `KeyVerification(prompt)` — peer key-verification prompt.
- `PacketsDropped(flow, count)` — backpressure on a subscriber-bound flow.

See [`core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt)
for current variants — adding a new variant is a SemVer-major change post-1.0.

## Metrics

The SDK does not bundle a metrics library. The recommended pattern is
to subscribe to `events` and convert each variant into a metric
emission in your own observability stack (Micrometer, OpenTelemetry,
Prometheus, etc.). A reference adapter is on the post-1.0 roadmap.

For ad-hoc performance checks, see [`docs/performance-expectations.md`](performance-expectations.md).

## Diagnostics CLI

`samples/cli` is the SDK's reference test harness. Useful commands when
diagnosing field issues:

| Command | Purpose |
|---|---|
| `cli connect <transport> <addr>` | One-shot handshake; prints terminal state. |
| `cli probe <runs> <transport> <addr>` | Stress-test connect/handshake/disconnect. Outputs per-run timing. |
| `cli events <transport> <addr>` | Stream `MeshEvent` flow as NDJSON ([ADR-013](decisions/013-proto-json-envelope.md)). |
| `cli health <transport> <addr>` | Snapshot of last-seen, RSSI, SNR for every known node. |

All commands accept `--format=json` for agent-consumable output.

## What the SDK does *not* do

- It does not write to stderr or any platform logger by default.
- It does not phone home or emit telemetry of its own.
- It does not persist diagnostic data between sessions (storage is
  consumer-driven via `:storage-sqldelight`, optional).

## Privacy expectations

`meshtastic-sdk` is part of an off-grid mesh-radio ecosystem;
operational privacy matters. The defaults reflect that:

- Silence by default (no `LogSink` → no logs).
- Frame logger off by default; explicit opt-in with a documented
  warning.
- CLI redacts PSKs and passkeys when frame logging is enabled.
- No network telemetry to the SDK author.

## References

- [ADR-011](decisions/011-logsink.md) — `LogSink` design.
- Audit finding B6 — separated frame logging from general logging.
- [`docs/performance-expectations.md`](performance-expectations.md)
