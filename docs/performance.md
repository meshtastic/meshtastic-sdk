# Performance: operational notes and gaps

This page is the operational view of SDK performance: what to expect at runtime, the buffer sizes in use, and what is not measured. [`performance-expectations.md`](./performance-expectations.md) explains where the time goes, and [`protocol.md`](./protocol.md) sets the wire-level ceiling.

## Benchmarking gap

The repository has no benchmark harness: no `kotlinx-benchmark`, JMH or Android Macrobenchmark. CI produces no performance numbers, so a regression surfaces only when it becomes a correctness failure, such as a test timing out or a flow buffer overflowing.

That is a deliberate scope cut before 1.0:

- The radio link tops out around 1 KB/s, so SDK CPU time is irrelevant against it.
- A benchmark suite would cost more in CI flakiness than it returns while internal shapes still change.
- Connect time and memory can be observed with `cli probe` (see "How to measure a regression").

A `kotlinx-benchmark` module is on the post-1.0 roadmap, as `:benchmarks/` running nightly rather than on every pull request.

## Expected throughput

| Path | Ceiling | Bottleneck |
|---|---|---|
| Inbound frame → engine → `events` emission | far above wire rate (well under 1 ms per frame on a 2020-era laptop) | wire and firmware |
| Outbound `client.sendText(...)` → bytes on the transport | about 5 ms of bookkeeping; the rest is firmware | wire and firmware |
| BLE writes | paced by Kable and the OS stack, one acknowledged write per connection interval | OS Bluetooth stack |
| TCP and WebSocket | LAN far above wire rate; the radio is the limit | wire |
| Serial | UART baud (115 200 bps); the 4-byte frame header adds a few percent | UART and wire |

The radio link is the ceiling for every end-to-end scenario. SDK overhead would have to be extreme to be visible.

## Memory profile

The engine's resident footprint is kilobytes, not megabytes:

- One actor coroutine and its inbox `Channel`.
- One bounded `MutableSharedFlow` per public stream (`nodes`, `packets`, `events`, `fromRadio`), none with replay.
- The NodeDB cache keyed by NodeNum, typically 10 to 100 entries on a real mesh and bounded by firmware.
- The pending-request map for outstanding admin and routing RPCs, usually empty.

Collectors that block in `collect { }` are the real risk; treat a hot-flow collector as if it ran on a UI thread.

## Buffer sizes

These are the values in use. Tune only with measurement.

| Surface | Buffer | When full |
|---|---|---|
| `packets` | `extraBufferCapacity = 128`, no replay | the new packet is dropped and `MeshEvent.PacketsDropped(Packets, 1)` emitted; the engine never waits on a collector |
| `nodes` | `extraBufferCapacity = 256`; every subscription starts with a fresh `Snapshot` | the change is dropped and `MeshEvent.PacketsDropped` emitted |
| `events` | `extraBufferCapacity = 64`, `DROP_OLDEST` | the oldest event is dropped |
| `fromRadio` | `extraBufferCapacity = 256`, `DROP_OLDEST` | the oldest envelope is dropped |
| Engine inbox and outbound channel | `Channel.UNLIMITED` | n/a; producers never block on the engine |
| BLE outbound write | one `ToRadio` per write, within the negotiated MTU | n/a |
| Serial frame assembler | an okio `Buffer` per stream; frames over 512 payload bytes are discarded and resynced, text lines over 1024 bytes dropped | n/a |
| TCP reads | 256-byte read chunks into the stream assembler | n/a |

## Latency-sensitive paths

Two paths shape perceived responsiveness; [`performance-expectations.md`](./performance-expectations.md#latency-sensitive-paths) gives the rationale and budgets.

1. Send to first byte: `client.send(...)` to the first byte on the transport takes under 5 ms. The rest is firmware and radio.
2. Inbound frame to `events` emission: under 1 ms on the engine dispatcher; collectors run on their own dispatchers.

Neither path holds a lock or blocks on I/O.

## Wire-level characteristics

[`protocol.md`](./protocol.md) covers:

- The 4-byte stream header on TCP and serial (§2).
- The 512-byte limit per `ToRadio` or `FromRadio` and the 233-byte limit on `Data.payload` (§18).
- Heartbeat cadence: 30 s on every transport, which BLE can opt out of (§16).
- The two-stage handshake (`want_config_id` 69420, then 69421), which keeps the NodeDB stream out of the config stage (§6).

## How to measure a regression

Until a benchmark harness exists:

1. Connect and handshake time: run `cli --json probe tcp <NODE_HOST> --runs <N>` on each SDK version and compare the `elapsedMs` values in the `probe-run` envelopes.
2. Engine memory: attach a JVM profiler to a long-running CLI session; the engine's retained set should level off within seconds of `Connected`.
3. Collector backpressure: watch `client.events` for `PacketsDropped` under synthetic load (B3 in [`manual-tests.md`](./manual-tests.md)).

## Known pitfalls

- Protocol logging (`Builder.protocolLogging`) allocates a string per envelope. It is off by default ([ADR-011](decisions/011-logsink.md)).
- A collector that blocks on `packets` or `nodes` loses items once its buffer fills.
- Kable and CoreBluetooth pace BLE writes; a burst blocks the transport coroutine, never the engine actor.

## References

- [`performance-expectations.md`](./performance-expectations.md): design rationale and end-to-end timing budgets.
- [`protocol.md`](./protocol.md): wire framing, MTU, handshake cadence.
- [`threading-model.md`](./threading-model.md): what runs where.
- [`observability.md`](./observability.md): measuring in production through `LogSink`.
- [ADR-002](decisions/002-architecture.md): the single-writer actor.
