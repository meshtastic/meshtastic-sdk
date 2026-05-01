# Performance — operational notes & gaps

> Companion to [`performance-expectations.md`](./performance-expectations.md)
> (the design-shaped "where the time goes" doc) and
> [`protocol.md`](./protocol.md) (wire-level framing constraints that
> dictate the ceiling).
>
> This file is the **operational** view: what to expect at runtime,
> recommended buffer sizes, and an explicit inventory of what we do
> **not** measure today.

## Benchmarking gap (current state)

**There is no benchmark harness in this repo.** No `kotlinx-benchmark`,
no `jmh`, no Android Macrobenchmark. CI does not produce performance
numbers, and a perf regression would only surface if it crossed into
correctness territory (a test timing out, a flow buffer overflowing).

This is a deliberate scope cut for 0.x:

- The radio link tops out at ~1 KB/s; SDK CPU is irrelevant against it.
- Adding a benchmark suite costs more in CI flakiness than it returns
  while we're pre-1.0 and changing internal shapes weekly.
- Memory and connect-time are observable today via the
  `cli probe` sub-command (see "Measuring" in
  [`performance-expectations.md`](./performance-expectations.md)).

A `kotlinx-benchmark`-based micro-benchmark module is on the post-1.0
roadmap. When it lands, it will live at `:benchmarks/` and run in a
nightly job (not on every PR).

## Expected throughput

| Path | Ceiling | Bottleneck |
|---|---|---|
| Inbound frame → engine → `events` emission | well above wire-rate (engine processes a frame in ≪ 1 ms on a 2020-era laptop) | wire / firmware |
| Outbound `client.sendText(...)` → bytes on transport | ~5 ms bookkeeping; rest is firmware | wire / firmware |
| BLE write throughput | governed by Kable / CoreBluetooth pacing — sustained writes are paced per-MTU per connection interval | OS BLE stack |
| TCP throughput | LAN ≫ wire; the radio is the limit | wire |
| Serial throughput | UART baud (typically 115 200 bps); wire-format adds ~12 % framing overhead | UART + wire |

The radio link is the ceiling for every end-to-end scenario. SDK
overhead would have to be catastrophic to be visible at all.

## Memory profile

The engine's resident footprint is **kilobytes, not megabytes**:

- One actor coroutine + a single mailbox `Channel`.
- One `MutableSharedFlow` per public surface (events, packets, …)
  with bounded replay (`replay = 0` for events; `replay = 1` for
  state flows).
- Node-DB cache keyed by `nodeNum`, typically 10–100 entries on a real
  mesh, bounded by firmware limits.
- Pending-request map for outstanding admin/routing RPCs, typically
  empty.

Subscribers that block in their `collect { }` are the real risk —
treat hot-flow collectors as if they were on a UI thread.

## Recommended buffer sizes

These are the values that work today; tune only with measurement.

| Surface | Buffer | Rationale |
|---|---|---|
| Transport `incoming` channel (BLE/serial/TCP) | `Channel.UNLIMITED` (unbounded) for in-process fakes; bounded ring per-transport in production | The engine drains fast; the bound exists to detect a stuck reader, not to gate steady state. |
| `events` `MutableSharedFlow` | `replay = 0`, `extraBufferCapacity = 64`, `onBufferOverflow = DROP_OLDEST` | Slow consumers get `MeshEvent.PacketsDropped` rather than back-pressuring the engine. |
| `packets` `MutableSharedFlow` | same shape as `events` | same rationale; the firmware will resend on rebroadcast if the mesh ACKs. |
| BLE outbound write | one MTU per write (Kable handles segmentation) | Larger writes get re-segmented anyway. |
| Serial frame assembler | 4 KiB scratch buffer per stream | Larger than any valid Meshtastic frame (`max_packet_size` ≤ 256 B); leaves room for resync after partial frames. |
| TCP read buffer | 4 KiB per connection | Aligned with default ktor-network buffering. |

## Latency-sensitive paths

Two paths matter for perceived responsiveness; see
[`performance-expectations.md`](./performance-expectations.md#latency-sensitive-paths)
for the design rationale and budgets.

1. **Send → ACK round-trip** — `client.send(...)` to first byte on the
   transport: < 5 ms. Rest is firmware + radio.
2. **Inbound frame → `events` emission** — < 1 ms on the engine
   dispatcher; subscribers run on their own dispatchers.

Neither path holds locks; neither blocks on I/O.

## Wire-level performance characteristics

See [`protocol.md`](./protocol.md) for:

- Sync-byte framing overhead (4 bytes per frame).
- `max_packet_size` per device (typically 237 B post-overhead).
- Heartbeat cadence (30 s default for TCP/serial; opt-in for BLE).
- Two-stage handshake (`config_id` 69420/69421) and why we send Stage 2
  before draining Stage 1's NodeInfo storm.

## How we'd measure a regression today

Until the benchmark harness lands:

1. **Connect time / handshake time**: `cli probe N tcp <host>` over N
   cycles, diff `payload.duration_ms` between SDK versions.
2. **Engine memory**: attach a JVM profiler to a long-running CLI
   session; the engine's retained set should plateau within seconds of
   `Connected`.
3. **Subscriber back-pressure**: enable `LogLevel.Debug` and watch for
   `PacketsDropped` events under a synthetic load (B3 in
   [`manual-tests.md`](./manual-tests.md)).

## Known footguns

Carried over from `performance-expectations.md`; restated here so
operators have one page to land on:

- **Frame logging at `Debug`** allocates a string per envelope. Off
  by default ([ADR-011](decisions/011-logsink.md)).
- **Many subscribers on hot flows** — `events`/`packets` use
  `MutableSharedFlow` with replay; subscribers that block pile up
  buffers.
- **BLE rate-limiting** — Kable / CoreBluetooth pace per-packet writes;
  bursts block the *transport* coroutine, never the engine actor.

## References

- [`performance-expectations.md`](./performance-expectations.md) —
  design rationale and end-to-end timing budgets.
- [`protocol.md`](./protocol.md) — wire framing, MTU, handshake
  cadence.
- [`threading-model.md`](./threading-model.md) — what runs where.
- [`observability.md`](./observability.md) — measuring in production
  via `LogSink`.
- [ADR-002](decisions/002-architecture.md) — single-writer actor.
