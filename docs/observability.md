# Observability

How to see what `meshtastic-sdk` and the radio are doing: SDK logging, frame-level protocol logging, the raw `FromRadio` tap, device logs, and the event stream. [ADR-011](decisions/011-logsink.md) records the logging design.

## Logging

The SDK has one logging hook, `LogSink`, a `fun interface` in [`Logging.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Logging.kt).

```kotlin
val client = RadioClient.Builder()
    .transport(tcp)
    .storage(InMemoryStorageProvider())
    .logger { level, tag, message, cause ->
        myAppLogger.log(level.toMyLevel(), "$tag/$message", cause)
    }
    .build()
```

Without a `LogSink` the SDK is silent: a library that handles keys doesn't write to stderr by default.

### Levels

`LogLevel` has six values. `NONE` is only the off switch for protocol logging.

| Level | Used for |
|---|---|
| `VERBOSE` | Per-packet traces and heartbeat ticks; high volume |
| `DEBUG` | Lifecycle transitions, RPC dispatch, send queueing, ACK timers |
| `INFO` | Handshake stage starts and completions, session ready, connect and disconnect |
| `WARN` | Recoverable anomalies: decode failures, transport errors, drops, identity rebinds |
| `ERROR` | Handshake timeouts and protocol violations that end the session |

### Tags

| Tag | Source |
|---|---|
| `MeshEngine` | The engine: lifecycle, handshake, routing, sends |
| `CommandDispatcher` | RPC registration, completion, and timeout |
| `Protocol` | Protocol logging, when enabled |
| `UsbSerialTransport` | `AndroidUsbSerialPorts` transports, when `open` gets a `logger` |

The builder's `LogSink` reaches the engine only. Transports don't receive it; `AndroidUsbSerialPorts.open(…, logger)` is the one transport that takes its own. Other transports report problems through exceptions and `TransportState.Error`.

The engine builds log messages lazily, so with `LogSink.Silent` no message string is ever constructed.

## Protocol logging

`Builder.protocolLogging(level, redactor)` logs every `FromRadio` and `ToRadio` envelope under the `Protocol` tag, prefixed `rx` or `tx`, at `level`, rendered by `redactor`. It's off by default (`LogLevel.NONE`) and also needs a `LogSink`: with `LogSink.Silent` nothing is logged.

```kotlin
val client = RadioClient.Builder()
    .transport(tcp)
    .storage(InMemoryStorageProvider())
    .logger(myLogSink)
    .protocolLogging(LogLevel.DEBUG, PayloadRedactor.Default)
    .build()
```

`PayloadRedactor.Default` logs the envelope variant and routing metadata (`from`, `to`, packet ID, port, payload size), the section name of a `config` or `moduleConfig`, the `queueStatus` counters (`free`, `maxlen`, `res`, `mesh_packet_id`), the `heartbeat` nonce, and the `xmodemPacket` `control`, `seq`, and buffer length. It never logs payload bytes, text, positions, PSKs, passkeys, config values, or file contents. A frame that doesn't decode as `ToRadio` is logged by size only.

A custom `PayloadRedactor` overrides `describe(FromRadio)` and `describe(ToRadio)`; what it includes is its owner's responsibility under [security.md](./security.md). Don't enable protocol logging in a production build, even with the default redactor: the envelopes still reveal mesh structure and timing.

## Raw `FromRadio` tap

`RadioClient.fromRadio: Flow<FromRadio>` carries every decoded inbound envelope, unfiltered and unredacted: handshake frames, config, channels, log records, and packets. Use it for recorders and protocol analyzers that need more than `packets`.

The tap is hot, has no replay, and never slows the engine: a collector more than 256 envelopes behind loses the oldest, unreported. Envelopes can carry channel PSKs and node positions, and the SDK neither redacts nor persists them, so treat a recording as secret.

## Device logs

The SDK doesn't turn device logging on; the host sets it in device config. Each link carries the firmware's log differently:

| Link | Where the log arrives | Device setting |
|---|---|---|
| Phone API (TCP, serial, BLE, WebSocket) | `FromRadio.log_record` envelopes, visible on `fromRadio` only; the engine doesn't surface them elsewhere | `security.debug_log_api_enabled` |
| BLE `logradio` characteristic | `BleTransport.deviceLogs(): Flow<LogRecord>` | `security.debug_log_api_enabled` |
| Serial text between frames | The `onTextLine: (String) -> Unit` callback of `JvmSerialPorts.open`, `AndroidSerialPorts.open`, or `AndroidUsbSerialPorts.open` | None. Firmware prints its log as text until the phone API starts on the port, then sends `log_record` instead when `debug_log_api_enabled` is set |

`deviceLogs()` is cold and opt-in: it subscribes when collected, unsubscribes on cancel, and doesn't survive a disconnect, so collect again after reconnecting. It buffers 64 records and drops the oldest; it skips a record truncated by the ATT payload size or one that doesn't decode. It completes empty on a radio without the characteristic and fails with `MeshtasticException.Transport` when the subscription can't start or the link drops. Use the phone API path or `deviceLogs()`, not both.

`onTextLine` runs on the serial reader thread, so it must not block; an exception it throws is swallowed.

## Engine event surface

`RadioClient.events: Flow<MeshEvent>` is where to observe session-level changes: transport errors, identity rebinds, storage degradation, protocol warnings, notifications, congestion, and dropped items. Dashboards and status indicators should subscribe to it rather than parse log lines. [error-taxonomy.md](./error-taxonomy.md#meshevent-reference) lists every variant, when it fires, and what to do. Adding a variant is a SemVer-major change after 1.0.

## Metrics

The SDK doesn't bundle a metrics library. Subscribe to `events`, `connection`, and `MessageHandle.state`, and convert them into emissions for your own stack, such as Micrometer, OpenTelemetry, or Prometheus. [performance-expectations.md](performance-expectations.md) covers ad hoc performance checks.

## Diagnostics CLI

`samples/cli` is the SDK's reference test harness. Each connecting command takes `--transport ble:NEEDLE | tcp:HOST[:PORT] | serial:PORT[:BAUD]`, parsed by `TransportTarget.parse`, and the root option `--json` switches output to NDJSON envelopes ([ADR-013](decisions/013-proto-json-envelope.md)).

| Command | Does |
|---|---|
| `cli scan ble`, `cli scan serial`, `cli scan tcp` | Find radios on one transport family |
| `cli info` | Connect once; print the own node and node count |
| `cli nodes` | Snapshot or stream the node table |
| `cli packets` | Stream `MeshPacket`s |
| `cli events` | Stream `MeshEvent`s |
| `cli health` | Exit 0 when the handshake completes and the own node is known |
| `cli send text` | Send a text message and wait for `Acked`, `Delivered`, or `Failed` |
| `cli probe ble`, `tcp`, `serial`, `all` | Connect-disconnect loops with per-run timing |

## What the SDK doesn't do

- Write to stderr or a platform logger by default.
- Send telemetry of its own anywhere.
- Persist diagnostic data. Storage holds radio state only.

## References

- [ADR-011](decisions/011-logsink.md): the `LogSink` design
- [security.md](./security.md): what may be logged
- [performance-expectations.md](performance-expectations.md)
