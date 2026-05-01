# Performance Expectations

What `meshtastic-sdk` aims for, where the bottlenecks actually live,
and how to measure regressions. This document is intentionally
*expectation*-shaped, not benchmark-shaped: real numbers depend on
device firmware, transport, host, and link conditions, none of which the
SDK controls.

## Where the time goes

A typical end-to-end "open SDK → first usable state" sequence:

| Phase | Typical wall time | Dominated by |
|---|---|---|
| Transport connect | TCP: < 50 ms LAN; BLE: 0.5–2 s (incl. discovery + bonding); serial: < 100 ms after port open | OS/transport stack — not the SDK |
| Handshake (`config_id` → `config_complete_id`) | 200–800 ms over BLE; 50–200 ms over TCP/serial | Device firmware producing config envelopes |
| Initial node-DB fill | 50–500 ms after handshake | Number of nodes in `NodeInfo` envelopes |
| First `client.connection == Connected` | Sum of the above | — |

The SDK's own CPU time is negligible against any of these phases. We
budget *engine processing time per envelope* at well under 1 ms on a
2020-era laptop; the actor-model design keeps that constant under load.

## Memory

The engine holds:

- One actor coroutine + a small mailbox `Channel`.
- A `MutableSharedFlow` per public flow surface (events, packets, etc.).
- A node-DB cache keyed by `nodeNum` (typically 10–100 entries on a
  real mesh; bounded by firmware limits).
- A pending-request map for outstanding admin/routing requests
  (typically empty; bounded by configurable timeout window).

Total resident overhead: kilobytes, not megabytes.

## Throughput

The Meshtastic radio link tops out at low single-digit kilobytes per
second. The SDK's framing/codec path can comfortably sustain orders of
magnitude more — the wire is the bottleneck, not the SDK. A
performance regression in the SDK would have to be obviously
catastrophic to be visible at all.

## Latency-sensitive paths

The two paths that matter for perceived responsiveness:

1. **Send → ACK round-trip.** The SDK adds < 5 ms of bookkeeping
   between accepting `client.send(...)` and emitting the framed bytes
   on the transport. The rest is firmware + radio time.
2. **Frame arrival → `events` emission.** Each inbound frame goes
   bytes → codec → engine actor → `MutableSharedFlow`. We budget < 1
   ms total on the engine dispatcher; subscribers run on their own
   dispatcher and don't back-pressure the engine.

Neither path holds locks; neither blocks on I/O.

## Measuring

The CLI's `probe` sub-command is the primary regression tool:

```bash
cli probe 50 tcp 192.168.1.42:4403 --format=json | jq '.payload.duration_ms'
```

50 connect/handshake/disconnect cycles against a real device; per-run
timings come back as NDJSON and can be diffed across SDK versions or
firmware builds.

For micro-level engine work, the test suite includes timing-sensitive
fixtures under `:testing` — but those are correctness tests, not
benchmarks. We have not yet wired a benchmark harness; one is on the
post-1.0 roadmap.

## Anti-goals

- The SDK does **not** chase throughput numbers. The radio link is the
  ceiling.
- The SDK does **not** add adaptive batching, retry storms, or
  speculative reads. Predictable behavior beats clever behavior in a
  protocol where every byte costs airtime.
- The SDK does **not** spin up thread pools. Coroutines on
  `Dispatchers.Default` plus the engine's single-thread dispatcher are
  the entire compute model.

## Known performance footguns

- **Frame logging at `Debug`** turns every envelope into a string
  allocation + `LogSink` callback. Off by default for both correctness
  ([ADR-011](decisions/011-logsink.md)) and performance reasons.
- **Many subscribers on hot flows** — `events` and `packets` are
  `MutableSharedFlow` with unlimited replay; subscribers that block in
  their `collect { }` lambda will pile up replay buffers. Treat
  subscribers as if they were on a UI thread: no I/O, no allocations
  per event you can avoid.
- **BLE rate-limit at high write frequency** — Kable / CoreBluetooth
  enforce per-packet pacing; bursting writes will block the *transport*
  coroutine, never the engine.

## References

- [ADR-002](decisions/002-architecture.md) — single-writer actor; why
  there are no locks to contend.
- [`docs/threading-model.md`](threading-model.md) — what runs where.
- [`docs/observability.md`](observability.md) — measuring in production.
