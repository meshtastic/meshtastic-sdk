# `samples:cli`: Meshtastic hardware test harness

One Kotlin/JVM binary (`cli`) for exercising and observing real Meshtastic nodes from a terminal or CI. Two modes share one subcommand tree:

- Headless: every subcommand exits with a stable code. Add `--json` for line-delimited NDJSON envelopes that agents and CI can parse.
- Interactive: `cli tui` renders a live [Mosaic][mosaic] dashboard.

[Clikt][clikt] handles argument parsing, `--help` and exit codes.

[mosaic]: https://github.com/JakeWharton/mosaic
[clikt]: https://ajalt.github.io/clikt/

## Build and run

```shell
./gradlew :samples:cli:installDist
./samples/cli/build/install/cli/bin/cli --help
```

Use the installed launcher. `./gradlew :samples:cli:run` works but strips ANSI escapes and can't drive an interactive terminal.

## Transport spec

Every session-opening subcommand takes one `--transport=<spec>` flag, parsed by `TransportTarget.parse` from `:core`:

| Spec | Example | Notes |
|---|---|---|
| `ble:NEEDLE` | `ble:1c10` | Substring match against the advertised name, peripheral name or identifier (`bleAdvertisementMatches`). An empty needle takes the first Meshtastic advertisement. The node must be bonded with the OS. |
| `tcp:HOST[:PORT]` | `tcp:meshtastic.local` | Port defaults to `4403`. |
| `serial:PORT[:BAUD]` | `serial:cu.usbmodem101` | Baud defaults to `115200`. Bare names and `/dev/*` paths both work. |

The probe subcommands (`cli probe ble|tcp|serial`) take a positional `<target>` in the same grammar without the scheme prefix, since a probe usually targets one device. `cli probe all` takes `--serial`, `--tcp` and `--ble` flags side by side.

## Subcommands

`cli <command> --help` is the full reference. In short:

| Command | Purpose |
|---|---|
| `scan ble [--seconds N]` | Stream Meshtastic Bluetooth advertisements, with bond status where the OS exposes it. |
| `scan serial` | List the visible serial ports with their USB vendor and product IDs. |
| `scan tcp --host HOST [--port P]` | Check that a host accepts a TCP connection; this is a reachability probe, not discovery. |
| `info --transport=…` | One-shot: own node and node count, then exit. |
| `nodes --transport=… [--watch]` | Snapshot or stream the node DB. |
| `packets --transport=… [--watch] [--filter portnum=…,from=0xHEX]` | Stream `MeshPacket`s. |
| `events --transport=… [--watch]` | Stream `MeshEvent`s. |
| `health --transport=…` | Exit 0 if the handshake completes and the own node is known. |
| `send text --transport=… -m "…" [--to DEST]` | Send a text and wait for Acked, Delivered or Failed. |
| `probe ble NEEDLE [--runs N]` | Bluetooth reconnect loop (the node must be bonded). |
| `probe tcp HOST[:PORT] [--runs N]` | TCP reconnect loop. |
| `probe serial PORT[:BAUD] [--runs N]` | Serial reconnect loop. |
| `probe all [--serial T] [--tcp T] [--ble N] [--runs N]` | Run every supplied transport back to back and aggregate. |
| `tui --transport=… [-m TEXT]` | Interactive Mosaic dashboard. |
| `conformance --transport=… [--peer-node=…] [-o transcript.md]` | Pre-release acceptance sweep: six scenarios (`cs1` to `cs6`), a markdown transcript, and a non-zero exit on any FAIL. See [docs/manual-tests.md](../../docs/manual-tests.md#scripted-via-cli-conformance). |

Durations accept `30s`, `5m`, `500ms` or a bare integer in milliseconds. `--json` is a root-level flag and switches the output of every subcommand.

## Examples

```shell
# Is the bench node reachable over TCP?
cli health --transport=tcp:meshtastic.local --timeout 10s

# Reconnect-loop a bonded Bluetooth node 5 times and keep the summary.
cli --json probe ble 1c10 --runs 5 | jq -c 'select(.type=="probe-summary")'

# Stream text messages from a USB node and print sender and body.
cli --json packets --transport=serial:cu.usbmodem101 --watch \
  | jq -cr 'select(.type=="packet" and .data.decoded.portnum=="TEXT_MESSAGE_APP")
            | [.data.from, .data.decoded.payload] | @tsv'

# Broadcast a message and wait for the terminal ack state.
cli send text --transport=tcp:192.168.1.180 -m "ping"
```

### Run all probes

```shell
cli --json probe all \
  --serial "${TEST_SERIAL_PORT:-}" \
  --tcp    "${TEST_TCP_HOST:-}" \
  --ble    "${TEST_BLE_DEVICE:-}" \
  --runs   "${TEST_PROBE_RUNS:-3}" \
  | tee /tmp/meshtastic-probes.ndjson \
  | jq -c 'select(.type=="probe-summary")'
```

Pass only the transports you have wired up; unset ones are skipped. The command exits `0` only when every configured transport saw at least one clean connect and no failures.

### Pre-release conformance sweep

```shell
# One node over TCP, headline scenarios:
cli conformance --transport=tcp:meshtastic.local --candidate=v0.3.0

# Two nodes, full sweep, transcript written for the release:
cli conformance \
  --transport=tcp:meshtastic.local \
  --peer-node='!aabbccdd' \
  --candidate=v0.3.0 \
  --output docs/release-history/v0.3.0-conformance.md

# Rerun selected scenarios:
cli conformance --transport=tcp:meshtastic.local --scenario cs3,cs5
```

Output is one line per scenario plus a markdown summary table. With `--json` the same data arrives as one `info` envelope per scenario and a final `conformance-summary` envelope. Exit code 0 means every scenario passed (SKIP allowed); 1 means at least one FAIL.

## Bluetooth bonding

Meshtastic requires a bonded device. Pair the node with the host OS before `cli probe ble` or `cli tui --transport=ble:…`:

- macOS: System Settings → Bluetooth.
- Linux: `bluetoothctl pair <MAC>` (find it with `scan on` and `devices`).
- Windows: Settings → Bluetooth → Add device.

`cli scan ble` shows bond status on Linux. macOS doesn't expose bond state in a form that matches Kable's identifier, so the column reads "bonding unknown".

## JSON envelope contract

One object per line, shaped `{"type":…, "ts":…, "data":{…}}`:

```json
{"type":"node","ts":1729500000123,"data":{"op":"snapshot","node":{"num":1}}}
```

Streaming commands always end with a `done` envelope:

```json
{"type":"done","ts":1729500010456,"data":{"reason":"timeout","exit":0}}
```

Errors go to stdout, so `--json` consumers see them, and to stderr as human text. `node`, `packet`, `event.notification` and `event.queueStatus` carry canonical proto-JSON from [`wire-moshi-adapter`][wma]: fields use the generated camelCase names (`longName`, `lastHeard`) and `bytes` fields are base64. [ADR-013](../../docs/decisions/013-proto-json-envelope.md) records the format.

[wma]: https://github.com/square/wire/tree/master/wire-library/wire-moshi-adapter

### Envelope types

| `type` | Emitted by |
|---|---|
| `scan-hit` | `scan ble`, `scan serial`, `scan tcp` |
| `info` | `info`, `send text` (queued and result), `conformance` |
| `node` | `nodes`, `nodes --watch` |
| `packet` | `packets`, `packets --watch` |
| `event` | `events`, `events --watch` |
| `probe-run`, `probe-summary` | `probe …` |
| `conformance-summary` | `conformance` |
| `error` | any failure, paired with a non-zero exit |
| `done` | always last on stdout for streaming commands |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Operation failed (handshake error, send rejected, probe failed) |
| 2 | Usage error (bad flag, missing argument) |
| 3 | Timeout (`--timeout`, `--await` or `--stream-timeout` exceeded) |
| 4 | No device found (empty scan, Bluetooth needle miss, TCP host unreachable) |
| 5 | Transport unsupported on this platform |
| 130 | Interrupted (Ctrl+C) |
