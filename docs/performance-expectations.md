# Performance expectations

This page says what `meshtastic-sdk` aims for, where the time actually goes, and how to measure a regression. It states expectations, not benchmarks: real numbers depend on firmware, transport, host and link conditions, none of which the SDK controls. [`performance.md`](./performance.md) has the operational details: buffer sizes, memory and known pitfalls.

## Where the time goes

A typical sequence from opening the SDK to a usable session:

| Phase | Typical wall time | Dominated by |
|---|---|---|
| Transport connect | TCP: under 50 ms on a LAN; BLE: 0.5 to 2 s with discovery and bonding; serial: under 100 ms after the port opens | the OS and transport stack, not the SDK |
| Stage 1 (`want_config_id` → `config_complete_id`) | 200 to 800 ms over BLE; 50 to 200 ms over TCP or serial | firmware producing config envelopes |
| Stage 2 (NodeDB) | 50 to 500 ms | the number of nodes the firmware streams |
| `client.connection == Connected` | the sum of the above, plus the session-key seed | — |

The SDK's own CPU time is negligible against every phase. Engine processing per envelope is budgeted at well under 1 ms on a 2020-era laptop, and the actor design keeps it constant under load.

## Throughput

The radio link tops out at low single-digit kilobytes per second. The SDK's framing and codec path sustains orders of magnitude more, so the wire is the bottleneck. An SDK regression would have to be severe to be visible.

## Latency-sensitive paths

Two paths shape perceived responsiveness:

1. Send to first byte. The SDK adds under 5 ms of bookkeeping between accepting `client.send(...)` and writing the framed bytes to the transport. The rest is firmware and radio time.
2. Frame arrival to `events` emission. Each inbound frame goes bytes → codec → engine actor → `MutableSharedFlow`, budgeted at under 1 ms on the engine dispatcher. Collectors run on their own dispatchers and never backpressure the engine.

Neither path holds a lock or blocks on I/O.

## Measuring

The CLI's `probe` subcommand is the main regression tool:

```shell
cli --json probe tcp <NODE_HOST> --runs 50
```

It runs 50 connect, handshake and disconnect cycles against a real node and prints one `probe-run` NDJSON envelope per cycle, with `elapsedMs` in `data`, then a `probe-summary`. Diff the timings across SDK versions or firmware builds.

The test suite's timing-sensitive fixtures under `:testing` are correctness tests, not benchmarks. A benchmark harness is on the post-1.0 roadmap.

## Anti-goals

- The SDK does not chase throughput numbers; the radio link is the ceiling.
- The SDK does not add adaptive batching, retry storms or speculative reads. Predictable behavior beats clever behavior when every byte costs airtime.
- The SDK starts no thread pools of its own. Coroutines on `Dispatchers.Default`, or the context passed to `Builder.coroutineContext(...)`, are the whole compute model.

## References

- [ADR-002](decisions/002-architecture.md): the single-writer actor, and why there are no locks to contend.
- [`threading-model.md`](threading-model.md): what runs where.
- [`observability.md`](observability.md): measuring in production.
