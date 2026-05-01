# Manual conformance suite

> Tests that require a real Meshtastic device. CI cannot exercise these — they run on a developer's bench (or eventually a hardware-loop runner). Each test produces a pass/fail line in a hand-edited `MANUAL-TEST-RESULTS.md` per release candidate.
>
> Owned by Phase 2+ (TCP transport + first vertical) and grows per phase.

## Scripted via `cli conformance`

Sprint 7 ships a `cli conformance` subcommand that scripts the **Phase 5 acceptance set** —
six scenarios (`cs1` … `cs6`) that map directly onto the manual entries below. Run it pre-release
against your bench radio:

```bash
./gradlew :samples:cli:installDist
samples/cli/build/install/cli/bin/cli conformance \
    --transport=tcp:meshtastic.local \
    --peer-node='!aabbccdd' \
    --candidate=v0.1.0-rc1 \
    --output MANUAL-TEST-RESULTS.md
```

The command prints one line per scenario, writes a markdown transcript matching the
"Recording results" template, and exits non-zero on any FAIL. Use `--scenario cs1,cs3` to
restrict the sweep when iterating on a single failure mode. Scenarios that need a second
device (cs4 traceRoute) SKIP cleanly when `--peer-node` is omitted, leaving the exit code
unaffected — a reviewer still sees the gap in the transcript.

The csN ids correspond to manual entries below as follows:

| Scenario | Manual analogue | What it asserts |
|---|---|---|
| cs1 | A1 / A4 | Handshake reaches `Connected` within 30 s and `ownNode` is non-null. |
| cs2 | C1 | Broadcast text resolves to `SendOutcome.Success` within 30 s. |
| cs3 | (new — Phase 2) | `client.admin.getOwner()` returns `AdminResult.Success(User)`. |
| cs4 | (new — Phase 2) | `client.routing.traceRoute(peer)` returns a `RouteDiscovery` with at least one hop. |
| cs5 | B1 | `client.nodeSnapshot()` has `≥ --min-nodes` (default 1) within 30 s. |
| cs6 | A4 | Disconnect + reconnect cycle preserves `NodeNum`. |

Scenarios that **cannot** be scripted today (BLE pairing prompts in I1, pre-handshake byte
discard in A5, sniffer-required nodeinfo absence in A6) still need manual execution — they
have no `cs*` entry and remain in the categories below.

## Hardware setup

Minimum:
- One Meshtastic device on a known firmware version (record in `MANUAL-TEST-RESULTS.md`).
- USB cable for serial path.
- WiFi/Ethernet path for TCP/HTTP.
- A second device (any Meshtastic radio in range) for unicast/ack/PKI tests. Phase 6+ can defer multi-device tests until a second unit is available.

Recommended:
- A desk-mounted "test stand" with both radios on stable power.
- BLE-capable laptop (Mac/Linux/Windows) for BLE tests; a phone is also fine.

## Test categories

Each test specifies: **transport** • **steps** • **expected** • **how to verify** • **firmware-feature gate** (if any).

### A. Connect & handshake

#### A1 — Cold connect over TCP completes
- **Transport:** TCP
- **Steps:**
  1. Power-cycle device.
  2. Wait until WiFi LED solid.
  3. Run `samples/cli/build/install/cli/bin/cli info --transport=tcp:meshtastic.local`.
- **Expected:** within 30 s, CLI prints `Connected.` followed by `Own node: 0x…`.
- **Verify:** stdout shows the sequence `Connecting → Configuring(STAGE_1_*) → Configuring(STAGE_2_*) → Configuring(SEEDING_SESSION) → Connected`.

#### A2 — Cold connect over BLE completes
- **Transport:** BLE
- **Status:** _DEPRECATED pending rewrite._ Scenario requires meshtastic Android app or similar BLE-capable client; the `samples/android-app` (formerly in this repo) has been moved to the separate [meshtastic/android](https://github.com/meshtastic/android) repository.
- **Steps:**
  1. Run a Meshtastic-capable BLE client (e.g., the official Android app from `meshtastic/android` repo); tap "Scan", select your device.
  2. Accept BLE pairing prompt if not previously bonded.
- **Expected:** Connected within 30 s; `ownNode` populated.

#### A3 — Cold connect over USB serial completes
- **Transport:** Serial JVM
- **Steps:**
  1. Plug device into USB.
  2. Identify port (`ls /dev/tty.*` mac, `dmesg | tail` linux).
  3. Run `samples/cli/build/install/cli/bin/cli info --transport=serial:ttyUSB0`.
- **Expected:** Connected within 30 s.

#### A4 — Disconnect/reconnect cycle is clean
- **Transport:** any
- **Steps:**
  1. After Connected, call `client.disconnect()`.
  2. Wait 5 s, call `client.connect()` again.
- **Expected:** clean Disconnected → Connected; `ownNode` repopulates with same NodeNum; no log spam between cycles.

#### A5 — Pre-handshake bytes are discarded
- **Transport:** TCP
- **Steps:**
  1. Connect, observe logs at `LogLevel.Debug`.
- **Expected:** any `FromRadio` arriving before Stage 1 send is logged as `dropped pre-handshake frame` and not surfaced to public flows.
- **Note:** can be hard to provoke; useful when chasing a regression.

#### A6 — Connect does not spam NodeInfo onto the LoRa mesh
- **Transport:** any (TCP/serial/BLE)
- **Context:** current firmware overloads `Heartbeat(nonce = 1)` as a "force-broadcast NodeInfo" sentinel. The SDK skips that nonce (first heartbeat is `nonce = 2`). This test verifies from the airwaves side that connects do not emit an unintended NodeInfo broadcast. Gates audit finding F-3.1.
- **Setup:** second Meshtastic radio in range, tuned to the same channel/region, running `meshtastic --listen --info` (or equivalent packet-capture mode).
- **Steps:**
  1. Sniffer: start listening, note a baseline of incoming packets for ~60 s.
  2. DUT: `cli info --transport=tcp:…` (or any transport). Repeat 3× with 10 s between connects.
  3. Sniffer: capture for ~60 s after the last connect.
- **Expected:** no `NODEINFO_APP` broadcast from the DUT's `NodeNum` during or immediately after the connect cycles (beyond the normal ~10 min cadence driven by the device itself).
- **Regression:** if a NodeInfo broadcast appears within a few seconds of each `connect()` call, the SDK is sending `Heartbeat(nonce = 1)` — re-verify `heartbeatNonce` initialisation in `MeshEngine.kt`.

### B. Steady state

#### B1 — `nodes` flow stitches Snapshot + deltas
- **Transport:** any
- **Steps:**
  1. Subscribe to `client.nodes`; collect into a list.
  2. Wait 60 s with the device's mesh active (need a second radio sending NodeInfo).
- **Expected:** first emission is `Snapshot(nodes = …)`; subsequent emissions are `Added` / `Updated` / `Removed`; never a second `Snapshot` on the same subscription.

#### B2 — Late subscriber gets a fresh Snapshot
- **Transport:** any
- **Steps:**
  1. After 30 s of B1, start a second collector on `client.nodes`.
- **Expected:** the second collector receives a `Snapshot` first (not a missed-deltas replay), reflecting the engine's current map.

#### B3 — `packets` survives slow consumer with `PacketsDropped` event
- **Transport:** any (BLE preferred — easier to saturate)
- **Steps:**
  1. Subscribe to `client.packets` with a 200 ms `delay` per item.
  2. Have a second radio fire 200 text messages back-to-back.
- **Expected:** `client.events` emits one or more `PacketsDropped(Packets, n)` with `n` summing to roughly the delta between sent and received counts.

#### B4 — Heartbeat keeps TCP alive
- **Transport:** TCP
- **Steps:**
  1. Connect, idle for 10 minutes.
- **Expected:** connection stays Connected; debug logs show `Heartbeat sent (nonce=N)` every 30 s.

#### B5 — Heartbeat keeps Serial alive
- **Transport:** Serial
- **Steps:** as B4.
- **Expected:** as B4.

#### B6 — BLE heartbeat opt-in works
- **Transport:** BLE
- **Steps:**
  1. Connect with default Builder (BLE heartbeat default-on).
  2. Reconnect with `Builder.disableBleHeartbeat()`; idle 10 min.
- **Expected:** first run shows heartbeat ticks; second run shows none. Both stay connected.

#### B7 — Liveness watchdog surfaces a silently-dead link
- **Transport:** TCP (easiest to simulate)
- **Context:** exercises the engine liveness watchdog (`MeshEngine.LIVENESS_TIMEOUT_MS = 60 s`) and the TCP-layer read timeout (`TcpTransport.READ_TIMEOUT_MS = 65 s`). Together they guarantee a half-open socket (e.g. firewall drops state, Wi-Fi AP yanked, NAT entry evicted) surfaces as a `TransportError` within ~60-70 s instead of hanging forever. Gates audit findings §2.2 / §2.3 / §2.4.
- **Steps:**
  1. Connect over TCP, reach `Connected`, verify traffic flows (e.g. `cli info`).
  2. Without closing the TCP session cleanly, partition the device from the host — either pull the device's Ethernet/Wi-Fi, or drop its packets at the host firewall (`sudo pfctl -E; echo "block drop from any to <device-ip>" | sudo pfctl -f -`).
  3. Watch `client.events` / `client.connection`.
- **Expected:**
  - Within ~60 s of the last successful `FromRadio`, one `MeshEvent.TransportError` is emitted with `error.message` containing "liveness timeout" (or "TCP read timeout" if the handshake stalled before `Ready`).
  - `ConnectionState` transitions to `Reconnecting(cause)` and eventually `Disconnected`.
  - Reconnect via `client.connect()` succeeds once the partition is lifted.
- **Regression:** if the session sits in `Connected` for minutes with no traffic after a partition, either the watchdog is not running in `transitionToReady` or `lastRxTimeMs`/`livenessBudgetMs` is being reset by something other than a decoded `FromRadio`.

### C. Send & ACK

#### C1 — Local broadcast text round-trips
- **Transport:** any
- **Steps:**
  1. `client.sendText("hello")`.
  2. Observe `MessageHandle.state`.
- **Expected:** `Queued → Sent → (Delivered if mesh acks rebroadcast)`.

#### C2 — Unicast text gets `Acked`
- **Transport:** any
- **Steps:**
  1. Identify second radio's NodeId.
  2. `client.sendText("hi", to = otherNode)`.
- **Expected:** `Queued → Sent → Acked`.

#### C3 — Cancel pre-Sent fails handle as `Cancelled`
- **Transport:** Serial (easier to predict timing) or any
- **Steps:**
  1. `val h = client.send(packet)`. Immediately call `h.cancel()` before observing `Sent`.
- **Expected:** `state = Failed(Cancelled)`; transport never sends the frame.

#### C4 — `PayloadTooLarge` thrown synchronously
- **Steps:**
  1. Build a `MeshPacket` whose encoded size exceeds device `max_packet_size`.
  2. Call `client.send(packet)`.
- **Expected:** `MeshtasticException.PayloadTooLarge` thrown before any handle is allocated.

#### C5 — Disconnect mid-send resolves handle to `Failed(Disconnected)`
- **Transport:** TCP (easiest to forcibly drop)
- **Steps:**
  1. Send a packet to a slow remote; while in `Sent` state with no ACK yet, kill TCP (`tcpkill` or pull the device's plug).
- **Expected:** `state` resolves to `Failed(Disconnected)` within the engine's transport-error window; the `await()`-ing coroutine returns the corresponding outcome (no leak).

### D. Admin RPCs

#### D1 — `getOwner` round-trips
- **Steps:** `client.admin.getOwner()`.
- **Expected:** `AdminResult.Success(User)`; `User.long_name` non-empty.

#### D2 — `setOwner` then `getOwner` reflects change
- **Steps:**
  1. `client.admin.setOwner(currentUser.copy(long_name = "Test ${Random.nextInt()}"))`.
  2. `client.admin.getOwner()`.
- **Expected:** new long_name observed.

#### D3 — `setTime` resolves clock skew
- **Steps:**
  1. Force a 5-min clock offset on the host (only if device has no GPS).
  2. Connect with `Builder.autoSyncTimeOnConnect(true)`.
- **Expected:** post-handshake, device's reported time aligns with host within 60 s window. `MeshEvent.ProtocolWarning` MAY appear if pre-correction skew was large.

#### D4 — Reboot triggers transport drop and reconnect
- **Steps:** `client.admin.reboot()`.
- **Expected:** `Reconnecting(cause = Transport(...))` follows; reconnect succeeds within ~60 s.

#### D5 — `editSettings` batches without intermediate reboot
- **Steps:**
  1. `client.admin.editSettings { setConfig(c1); setChannel(ch1); setOwner(u1); }`.
- **Expected:** single device reboot at the end, not three.

#### D6 — `SessionKeyExpired` auto-retries once
- **Steps:**
  1. Hard to provoke deterministically. With access to firmware logs, force the device's `expectedSessionKey` to expire (idle ~15 min between connect and admin call).
  2. Issue any admin call.
- **Expected:** debug logs show `SessionKeyExpired observed; refreshed; retrying`; final result is `Success(...)` (not `SessionKeyExpired`).

### E. Storage

#### E1 — NodeDB persists across disconnect/reconnect
- **Transport:** any
- **Steps:**
  1. Connect; let NodeDB populate (≥3 nodes); disconnect.
  2. Block transport so handshake fails (e.g., disable WiFi); reconnect attempt fails.
  3. Inspect `client.nodeSnapshot()` while in `Reconnecting` — actually expects `NotConnected` exception.
  4. Restore transport; reconnect.
  5. While `Configuring(STAGE_2_DRAINING)`, late subscriber to `nodes` should receive Snapshot from storage immediately (no need to wait for full Stage 2 drain).
- **Expected:** late-subscriber Snapshot during reconnect contains pre-disconnect nodes (within last-seen TTL).

#### E2 — NodeNum mismatch atomically clears storage
- **Steps:**
  1. Connect to device A on TCP `meshtastic.local`. Populate NodeDB.
  2. Disconnect.
  3. Plug in different device B (factory-fresh) on the same hostname.
  4. Connect.
- **Expected:** during handshake, `MeshEvent.ProtocolWarning("identity rebound to new NodeNum")` emits; post-Connected `nodeSnapshot()` contains only device B's NodeDB.

#### E3 — Storage failure mid-session escalates to reconnect
- **Steps:**
  1. Connect with a `StorageProvider` that begins failing writes after some time (custom test impl).
- **Expected:** `MeshEvent.ProtocolWarning("storage write failed; retrying")` once; on second consecutive failure, `Reconnecting(cause = StorageUnavailable)`.

### F. PKI / DM

#### F1 — Channel hash filter is honored
- **Transport:** any, with two channels configured
- **Steps:**
  1. Send a text on channel 0 from peer.
  2. Send a text on channel 1 from peer.
  3. Subscribe to `client.packets`.
- **Expected:** both arrive (host belongs to both); `MeshPacket.channel` reflects the index.

#### F2 — Deferred decrypt re-attempts on key arrival
- **Steps:**
  1. Connect to a device that has channel 1 with PSK X.
  2. Receive an encrypted packet on channel 1.
  3. Have peer change channel 1 PSK to Y; the device receives the new Channel proto.
  4. New incoming packet on channel 1 with PSK Y.
- **Expected:** old packet may surface with PSK X decrypt; new packet uses PSK Y. No engine error.

#### F3 — PKI DM arrives already-decrypted
- **Steps:**
  1. Configure PKI between local and peer (firmware-side).
  2. Peer sends PKI DM.
- **Expected:** `MeshPacket.decoded.payload` is cleartext on receipt; the SDK does NOT see the encrypted form. Confirms "device decrypts PKI" invariant.

### G. Backpressure & lifecycle edge cases

#### G1 — `disconnect()` is idempotent
- **Steps:** call `client.disconnect()` 3× consecutively.
- **Expected:** no exception; state reaches `Disconnected` once.

#### G2 — `connect()` while `Connected` throws `AlreadyConnected`
- **Steps:** call `connect()` again after first connect succeeds.
- **Expected:** `MeshtasticException.AlreadyConnected`. (Documented in error-taxonomy as an intentional non-no-op.)

#### G3 — Cancellation of `connect()` mid-handshake unwinds cleanly
- **Steps:**
  1. `withTimeout(2_000) { client.connect() }` (will time out before Stage 2).
- **Expected:** `TimeoutCancellationException` from `withTimeout`; `client.connection.value == Disconnected`; transport fully torn down (verifiable via OS sockets/file handles).

#### G4 — `HandshakeTimeout` recovery
- **Transport:** TCP (easiest to provoke by silencing the device mid-handshake)
- **Steps:**
  1. Connect; while `Configuring(STAGE_1_DRAINING)`, block the device's outbound (e.g., `iptables -A OUTPUT -p tcp --sport 4403 -j DROP` on a Linux DUT, or pull the WiFi antenna on a portable device).
  2. Wait ≥ engine's Stage 1 timeout (default 30 s; see `MeshEngine.kt`).
  3. Restore connectivity.
  4. Call `client.connect()` again.
- **Expected:**
  - First `connect()` resolves with `MeshtasticException.HandshakeTimeout(stage = "Stage1Draining")`.
  - `client.connection.value == Disconnected` (or `Reconnecting` if the engine elects to retry — log line will say which).
  - The retry succeeds within the usual 30 s window; `events` emits a `ProtocolWarning` describing the prior timeout.
  - No coroutine leak: thread/coroutine count returns to baseline after `Disconnected`.
- **Repeat** for `stage = "Stage2Draining"` and `"SeedingSession"` — block at the corresponding handshake stage.

### H. Storage migration & durability

#### H1 — Schema v1 → v2 migration drops orphaned `messages` table
- **Transport:** any
- **Pre-req:** A SQLite database file written by SDK ≤ v0.0.x (schema v1 — has the `messages` table). Easiest to obtain by checking out the previous tag, running A1 once against any device, then upgrading.
- **Steps:**
  1. Locate the database file used by the SDK on your platform (Android: app data dir; JVM: `~/.meshtastic-sdk/<identity>.db`; iOS: app documents).
  2. Verify table presence pre-migration:
     ```bash
     sqlite3 <db> ".schema messages"     # should show the v1 table
     ```
  3. Launch the upgraded SDK and connect once.
  4. Re-inspect:
     ```bash
     sqlite3 <db> ".tables"
     sqlite3 <db> "PRAGMA user_version;"   # should be 2
     ```
- **Expected:**
  - `.tables` no longer lists `messages`.
  - `PRAGMA user_version` returns `2`.
  - All `nodes` / `channels` / `configs` rows are preserved.
  - SDK logs a single migration line at `Info`; no errors.
- **Reference:** [`storage-sqldelight/.../migration_1__2.sqm`](../storage-sqldelight/src/commonMain/sqldelight/databases/2.sqm).

#### H2 — WAL mode + `synchronous=NORMAL` survives app crash
- **Transport:** any (storage is transport-agnostic)
- **Pre-req:** A device has populated NodeDB (≥ 3 nodes).
- **Steps:**
  1. Run the SDK to populated state (NodeDB ≥ 3 nodes, last-seen recent).
  2. Verify PRAGMAs (iOS / macOS — JVM/Android equivalents differ per driver):
     ```bash
     sqlite3 <db> "PRAGMA journal_mode;"  # expect: wal
     sqlite3 <db> "PRAGMA synchronous;"   # expect: 1  (NORMAL)
     ```
  3. **Hard-kill** the host process mid-write. Two ways to provoke:
     - `kill -9 <pid>` immediately after a `saveNode(...)` log line.
     - On Android: force-stop the app from Settings while a transmission is in flight.
  4. Confirm `<db>-wal` and `<db>-shm` files exist on disk (WAL artefacts).
  5. Restart the SDK; let it re-open storage *without* connecting to a device first.
  6. Read back via `client.nodeSnapshot()` (or query directly:
     `sqlite3 <db> "SELECT count(*) FROM nodes;"`).
- **Expected:**
  - SQLite recovers cleanly on next open; `<db>-wal` is checkpointed
    automatically (no manual recovery needed).
  - At-most-one in-flight transaction is lost (acceptable per ADR-007 /
    [`performance.md`](./performance.md) durability stance).
  - All previously-committed nodes are present.
  - No `database is locked` or `disk image is malformed` errors in the
    log on subsequent connect.

#### H3 — Identity rebind clears storage atomically
- **Steps:** see E2 in section E above (kept as the canonical scenario).
- **Note:** H3 is a pointer; do not duplicate the test entry.

### I. BLE bonding lifecycle

#### I1 — `TransportState.Bonding` surfaces during initial pairing
- **Transport:** BLE
- **Pre-req:** A device that has **not** previously bonded with the host (factory-reset or "Forget device" in OS settings).
- **Steps:**
  1. Subscribe to the transport's `state` flow (the easiest path is `cli --json events --transport=ble:<needle> --watch` — `state` envelopes carry the transport phase) or attach a debug collector in code.
  2. Initiate `client.connect()`.
  3. Observe the OS pairing prompt (Android: system dialog; iOS: pairing pop-up; macOS: keychain prompt).
  4. **Do not accept yet.** Note the transport state.
  5. Accept the prompt.
- **Expected:**
  - State sequence: `Disconnected → Connecting → Bonding → Connected`.
  - While in `Bonding`, the engine has **not** started the handshake clock — UI can render a "Confirm pairing on your device" prompt without racing `HandshakeTimeout`.
  - If the user dismisses / times out the system prompt: state transitions to `Error(cause = …, recoverable = false)`; `client.connect()` resolves with a transport-level failure.
- **Reference:** [`Transport.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) (`TransportState.Bonding` doc).

#### I2 — Already-bonded device skips `Bonding`
- **Transport:** BLE
- **Pre-req:** Same device + host that completed I1 successfully.
- **Steps:**
  1. Disconnect and reconnect within the same OS session (no re-pair).
- **Expected:**
  - State sequence: `Disconnected → Connecting → Connected` (no
    `Bonding` emitted; OS reports the bond cached).
  - Handshake proceeds immediately on `Connected`.

## Recording results

For each release candidate, copy this template into `MANUAL-TEST-RESULTS.md` at repo root and fill in:

```
# Manual Test Results — vX.Y.Z RC<n>

- Tester: <github handle>
- Date: <YYYY-MM-DD>
- Devices: <model>/<firmware> + <model>/<firmware>
- Host: <macOS/Linux/Windows + version>

## Connect & handshake
- A1 (TCP cold connect): PASS (`Connected` in 12 s)
- A2 (BLE cold connect): PASS (`Connected` in 18 s)
- A3 (Serial cold connect): N/A (no USB cable on hand)
- A4 (reconnect cycle): PASS
- A5 (pre-handshake discard): SKIPPED (cannot provoke)

## Steady state
...
```

Failures MUST file a GitHub issue with the test ID and a copy of the relevant logs. Phase 5 ABI freeze requires a clean A/B/C section pass; D/E/F/G are nice-to-have until 1.0.

## Future: hardware-in-the-loop CI

Post-1.0, we add a self-hosted GitHub Actions runner with a fixed pair of devices on a USB hub. Tests A1, A3, A4, B4, B5, C1, C2, D1, D2, D4, E1 become an automated nightly job (`hw-loop.yml`). Until then, this document is the manual replacement.

## Related

- [`api-reference.md`](./api-reference.md) — what each test exercises
- [`architecture/handshake-fsm.md`](./architecture/handshake-fsm.md) — A1/A4/A5 background
- [`error-taxonomy.md`](./error-taxonomy.md) — D6/G2 expectations
- `samples/cli/README.md` — script flags used in test commands
