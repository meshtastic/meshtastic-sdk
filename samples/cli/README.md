# `samples:cli` — Meshtastic physical test harness

One Kotlin/JVM binary (`cli`) for exercising and observing real Meshtastic radios
from the terminal or CI. Two modes share one subcommand tree:

- **Headless** — every subcommand exits with a stable code. Add `--json` for
  line-delimited NDJSON envelopes that agents and CI can parse.
- **Interactive** — `cli tui` renders a live [Mosaic][mosaic] dashboard.

Argument parsing, `--help` generation, and exit handling are done by [Clikt][clikt].

[mosaic]: https://github.com/JakeWharton/mosaic
[clikt]: https://ajalt.github.io/clikt/

## Build & run

```bash
./gradlew :samples:cli:installDist
./samples/cli/build/install/cli/bin/cli --help
```

The produced launcher script is what you want: `./gradlew :samples:cli:run` will
work, but strips ANSI escapes and can't drive an interactive TTY.

## Transport spec

Every session-opening subcommand takes one unified `--transport=<spec>` flag.

| Spec | Example | Notes |
|---|---|---|
| `ble:NEEDLE` | `ble:1c10` | Substring match against ad name / peripheral name / identifier. Empty needle = first Meshtastic ad. Device must be OS-bonded. |
| `tcp:HOST[:PORT]` | `tcp:meshtastic.local` | Port defaults to `4403`. |
| `serial:PORT[:BAUD]` | `serial:cu.usbmodem101` | Baud defaults to `115200`. Bare names and `/dev/*` absolute paths both work. |

Probe subcommands (`cli probe ble|tcp|serial`) take a positional `<target>` in
the same grammar (minus the scheme prefix) because you almost always run them
against a single device; `cli probe all` accepts three parallel
`--serial / --tcp / --ble` flags.

## Subcommands

Run `cli <command> --help` for the authoritative reference; the short list:

| Command | Purpose |
|---|---|
| `scan ble` / `scan serial` / `scan tcp` | Discover candidates on one transport family. |
| `info --transport=…` | One-shot: own node + node count, then exit. |
| `nodes --transport=… [--watch]` | Snapshot or stream the node DB. |
| `packets --transport=… [--watch] [--filter portnum=…,from=0xHEX]` | Stream `MeshPacket`s. |
| `events --transport=… [--watch]` | Stream high-level `MeshEvent`s. |
| `health --transport=…` | Exit 0 iff handshake completes and ownNode is known. |
| `send text --transport=… -m "…" [--to DEST]` | Send a text and await Acked/Delivered/Failed. |
| `probe ble NEEDLE [--runs N]` | BLE reconnect loop (device must be bonded). |
| `probe tcp HOST[:PORT] [--runs N]` | TCP reconnect loop. |
| `probe serial PORT[:BAUD] [--runs N]` | Serial reconnect loop. |
| `probe all [--serial T] [--tcp T] [--ble N] [--runs N]` | Run every supplied transport back-to-back and aggregate. |
| `tui --transport=… [-m TEXT]` | Interactive Mosaic dashboard. |
| `conformance --transport=… [--peer-node=…] [-o transcript.md]` | One-shot Phase 5 acceptance sweep — six scenarios (cs1…cs6), markdown transcript, exits non-zero on any FAIL. See [docs/manual-tests.md](../../docs/manual-tests.md#scripted-via-cli-conformance). |

Durations accept `30s`, `5m`, `500ms`, or a bare integer (ms). `--json` is a
root-level flag and switches the output of every subcommand.

## Examples

```bash
# Is my bench radio reachable over TCP?
cli health --transport=tcp:meshtastic.local --timeout 10s

# Reconnect-loop a bonded BLE device 5 times and capture the summary.
cli --json probe ble 1c10 --runs 5 | jq -c 'select(.type=="probe-summary")'

# Stream text messages from a USB radio and pretty-print sender + body.
cli --json packets --transport=serial:cu.usbmodem101 --watch \
  | jq -cr 'select(.type=="packet" and .data.decoded.portnum=="TEXT_MESSAGE_APP")
            | [.data.from, .data.decoded.payload] | @tsv'

# Broadcast a message and wait for the terminal ack state.
cli send text --transport=tcp:192.168.1.180 -m "ping"
```

### Run all probes (was `test-probes.sh`)

```bash
cli --json probe all \
  --serial "${TEST_SERIAL_PORT:-}" \
  --tcp    "${TEST_TCP_HOST:-}" \
  --ble    "${TEST_BLE_DEVICE:-}" \
  --runs   "${TEST_PROBE_RUNS:-3}" \
  | tee /tmp/meshtastic-probes.ndjson \
  | jq -c 'select(.type=="probe-summary")'
```

Pass only the transports you actually have wired up; unset ones are skipped.
The command exits `0` only when every configured transport saw at least one
clean connect with zero failures.

### Pre-release conformance sweep

```bash
# Single device, TCP, headline scenarios:
cli conformance --transport=tcp:meshtastic.local --candidate=v0.1.0-rc1

# Two devices, full sweep, write the transcript for the release-candidate doc:
cli conformance \
  --transport=tcp:meshtastic.local \
  --peer-node='!aabbccdd' \
  --candidate=v0.1.0-rc1 \
  --output MANUAL-TEST-RESULTS.md

# Iterate on a single failure:
cli conformance --transport=tcp:meshtastic.local --scenario cs3,cs5
```

Output is a human-readable per-scenario line plus a markdown summary table at
the end. Add `--json` for the same data as one `info` envelope per scenario plus
a final `conformance-summary` envelope. Exit code 0 = all PASS (SKIPs allowed),
1 = at least one FAIL.

## BLE bonding

Meshtastic requires a bonded device. Pair it with the host OS *before* using
`cli probe ble` / `cli tui --transport=ble:…`:

- **macOS:** System Settings → Bluetooth → pair device.
- **Linux:** `bluetoothctl pair <MAC>` (scan via `scan on`, `devices`).
- **Windows:** Settings → Bluetooth → Add device.

`cli scan ble` shows bonding status on Linux; on macOS the OS does not expose
bond state in a form that can be correlated with Kable's identifier, so the
column reads "bonding unknown".

## JSON envelope contract

One object per line, shaped `{"type":…, "ts":…, "data":{…}}`:

```json
{"type":"node","ts":1729500000123,"data":{"op":"snapshot","node":{ … proto JSON … }}}
```

Streaming commands always end with a `done` envelope:

```json
{"type":"done","ts":1729500010456,"data":{"reason":"timeout","exit":0}}
```

Errors go to stdout (so `--json` consumers see them) *and* to stderr (human
text). `node`, `packet`, `event.notification`, and `event.queueStatus` carry
**canonical proto-JSON** produced by [`wire-moshi-adapter`][wma]; fields use
generated camelCase (e.g. `longName`, `lastHeard`) and `bytes` are base64.

[wma]: https://github.com/square/wire/tree/master/wire-library/wire-moshi-adapter

### Envelope types

| `type` | Emitted by |
|---|---|
| `scan-hit` | `scan ble`, `scan serial`, `scan tcp` |
| `info` | `info`, `send text` (queued/result) |
| `node` | `nodes`, `nodes --watch` |
| `packet` | `packets`, `packets --watch` |
| `event` | `events`, `events --watch` |
| `probe-run` / `probe-summary` | `probe …` |
| `error` | any failure (paired with a non-zero exit) |
| `done` | always last on stdout for streaming commands |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Operation failed (handshake error, send rejected, probe failed, …) |
| 2 | Usage error (bad flag, missing required arg) |
| 3 | Timeout (`--timeout` / `--await` / `--stream-timeout` exceeded) |
| 4 | No device found (scan empty, BLE needle miss, TCP unreachable) |
| 5 | Transport unsupported on this platform |
| 130 | User interrupt (Ctrl+C) |
