# Manual conformance suite

These tests need a real Meshtastic node, so CI cannot run them; they run on a developer's bench. Each test produces a pass or fail line in a hand-edited results file per release candidate (see "Recording results").

## Scripted via `cli conformance`

The `cli conformance` subcommand scripts six scenarios (`cs1` to `cs6`) that map onto the manual entries in the following sections. Run it before a release against your bench node:

```shell
./gradlew :samples:cli:installDist
samples/cli/build/install/cli/bin/cli conformance --transport=tcp:<NODE_HOST> --peer-node='<PEER_NODE_ID>' --candidate=<VERSION_RC> --output MANUAL-TEST-RESULTS.md
```

`<NODE_HOST>` is the node's hostname or IP address, `<PEER_NODE_ID>` a second node's id in `!xxxxxxxx` form, and `<VERSION_RC>` the release candidate tag, such as `v0.3.0-rc1`.

The command prints one line per scenario, writes a markdown transcript matching the "Recording results" template, and exits non-zero on any FAIL. `--scenario cs1,cs3` restricts the run to those scenarios. Scenarios that need a second node (cs4) SKIP when `--peer-node` is omitted without affecting the exit code, and the transcript still shows the gap.

| Scenario | Manual analogue | What it asserts |
|---|---|---|
| cs1 | A1, A4 | The handshake reaches `Connected` within 30 s and `ownNode` is non-null. |
| cs2 | C1 | A broadcast text resolves to `SendOutcome.Success` within 30 s. |
| cs3 | D1 | `client.admin.getOwner()` returns `AdminResult.Success(User)`. |
| cs4 | — | `client.routing.traceRoute(peer)` returns a `RouteDiscovery` with at least one hop. |
| cs5 | B1 | `client.nodeSnapshot()` has at least `--min-nodes` nodes (default 1) within 30 s. |
| cs6 | A4 | A disconnect and reconnect cycle keeps the same `NodeNum`. |

Scenarios that cannot be scripted (BLE pairing prompts in I1, stale-frame discard in A5, the over-the-air NodeInfo check in A6) still run by hand.

## Hardware setup

Minimum:

- One Meshtastic node on a known firmware version, recorded in the results file.
- A USB cable for the serial path.
- Wi-Fi or Ethernet for TCP and WebSocket.
- A second node in range for unicast, ACK and PKI tests. Multi-node tests can wait until a second node is available.

Recommended:

- A bench stand with both nodes on stable power.
- A laptop with Bluetooth (macOS, Linux or Windows) for BLE tests; a phone also works.

## Test categories

Each test gives the transport, steps, expected result, how to verify it, and any firmware feature it needs.

### A. Connect and handshake

#### A1: Cold connect over TCP completes
- **Transport:** TCP
- **Steps:**
  1. Power-cycle the node.
  2. Wait until it is on the network.
  3. Run `samples/cli/build/install/cli/bin/cli info --transport=tcp:<NODE_HOST>`.
- **Expected:** within 30 s the CLI prints `Connected.` followed by `Own node: 0x…`.
- **Verify:** `client.connection` moves `Connecting → Configuring(Stage1) → Configuring(Settling) → Configuring(Stage2) → Connected`.

#### A2: Cold connect over BLE completes
- **Transport:** BLE
- **Steps:**
  1. Run `samples/cli/build/install/cli/bin/cli info --transport=ble:<NEEDLE>`, where `<NEEDLE>` is part of the node's advertised name or address (empty for the first Meshtastic advertisement).
  2. Accept the pairing prompt if the node is not bonded.
- **Expected:** `Connected` within 30 s; `ownNode` populated.

#### A3: Cold connect over USB serial completes
- **Transport:** Serial (JVM)
- **Steps:**
  1. Plug the node into USB.
  2. Find the port with `samples/cli/build/install/cli/bin/cli scan serial`.
  3. Run `samples/cli/build/install/cli/bin/cli info --transport=serial:<PORT>`.
- **Expected:** `Connected` within 30 s.

#### A3b: Cold connect over Android USB serial (usb-serial-for-android) completes
- **Transport:** Serial (Android), `AndroidUsbSerialPorts`
- **Steps:**
  1. Plug a node into the phone's USB-C port (OTG). Dismiss the app chooser Android raises for the device; picking an app hands the device to it.
  2. With the phone on adb, run `./gradlew :transport-serial:connectedAndroidTest` (`UsbSerialConformanceTest`).
  3. Select **Allow** on the USB permission dialog from `org.meshtastic.sdk.transport.serial.test`.
- **Expected:** `list(context)` shows the node; `Connected` within 30 s; `configBundle` set; a text to the own node resolves `Success`; `Disconnected` after.
- **Note:** a Seeed T1000-E reports its USB product name as `T1000-E-BOOT` while running firmware. That is the descriptor, not DFU mode.

#### A4: Disconnect and reconnect cycle is clean
- **Transport:** any
- **Steps:**
  1. After `Connected`, call `client.disconnect()`.
  2. Wait 5 s and call `client.connect()` again.
- **Expected:** a clean `Disconnected → Connected`; `ownNode` comes back with the same NodeNum; no log spam between cycles.

#### A5: Stale frames are discarded
- **Transport:** TCP
- **Steps:** connect with protocol logging on (`Builder.protocolLogging(LogLevel.DEBUG)`).
- **Expected:** a `FromRadio` that arrives before the Stage 1 `want_config_id` reaches no public flow.
- **Note:** hard to provoke; useful when chasing a regression.

#### A6: Connect does not broadcast NodeInfo on the mesh
- **Transport:** any
- **Context:** firmware treats `Heartbeat(nonce = 1)` as a request to broadcast the node's NodeInfo. The SDK's first heartbeat nonce is 2. This test checks over the air that connecting triggers no broadcast.
- **Setup:** a second node in range on the same channel and region, running `meshtastic --listen --info` or another packet capture.
- **Steps:**
  1. On the sniffer, record a baseline for about 60 s.
  2. On the node under test, run `cli info --transport=<SPEC>` three times, 10 s apart.
  3. Keep capturing for about 60 s after the last connect.
- **Expected:** no `NODEINFO_APP` broadcast from the tested node during or right after the connects, beyond its own roughly 10-minute cadence.
- **Regression:** a NodeInfo broadcast within seconds of each connect means the SDK sent nonce 1; check `FIRST_HEARTBEAT_NONCE` in `MeshEngine.kt`.

#### A7: A TCP node is discovered over mDNS and connects
- **Transport:** TCP, `TcpDiscovery` (`JvmTcpDiscovery`, `AndroidTcpDiscovery`, `IosTcpDiscovery`)
- **Setup:** a Wi-Fi or Ethernet node on the host's LAN segment, with the firmware's mDNS responder running (it starts once the node has an IP address). On Android the app holds `INTERNET`; on iOS the Info.plist has `NSLocalNetworkUsageDescription` and `NSBonjourServices` = `_meshtastic._tcp`.
- **Steps:**
  1. Collect `discover()` and log each event.
  2. Power-cycle the node, then power it off.
  3. Take the first `Found`, call `toTransport()`, and connect a `RadioClient` with it.
- **Expected:** `Found` arrives within about 10 s of the node getting an address, with `port = 4403`, an IPv4 address first in `addresses`, and `shortName`, `nodeId` (`!xxxxxxxx`) and `pioEnv` matching the node. The client reaches `Connected`. After power-off a `Lost` with the same `serviceName` arrives (at once on a clean shutdown, after the record TTL otherwise). Cancelling the collector stops discovery.
- **Verify:** CI only compiles the iOS target, so this is the real check of `IosTcpDiscovery`. Deny the iOS local-network prompt once and confirm the flow fails with `MeshtasticException.Transport`.

#### A8: WebSocket connect, displacement and limits
- **Transport:** WebSocket (`WebSocketTransport`)
- **Firmware:** a node built with firmware#12412 (ESP32) or #12413 (meshtasticd); plain `ws://` on meshtasticd also needs #12414.
- **Steps:**
  1. Connect a `RadioClient` with `WebSocketTransport("ws://<NODE_HOST>/api/v1/ws")`.
  2. Leave it idle for 20 minutes.
  3. Connect a second client (another host, or the web client) to the same node.
  4. On a `wss://` node with a self-signed certificate, connect without a trusting `HttpClient`.
  5. Repeat step 1 from a browser page on the JS or Wasm target.
- **Expected:** `Connected` within 30 s; the session survives the idle period (the heartbeat beats the 15-minute idle close); the first client's transport reports `TransportState.Error(recoverable = false)` with close 4001, or, on ESP32, after five short sessions that end without a close frame; step 4 fails `connect()` with `MeshtasticException.Transport` naming the certificate; step 5 behaves as step 1 within the browser's mixed-content rules.

#### A9: Serial debug text arrives as lines
- **Transport:** Serial
- **Steps:**
  1. Open the port with `JvmSerialPorts.open(<PORT>, 115200) { line -> println(line) }` (or `AndroidSerialPorts.open`).
  2. Enable the serial debug console on the node and connect a `RadioClient`.
- **Expected:** the node's log lines print one per callback, without terminators; the handshake and sends are unaffected.

### B. Steady state

#### B1: `nodes` flow stitches Snapshot and deltas
- **Transport:** any
- **Steps:**
  1. Subscribe to `client.nodes` and collect into a list.
  2. Wait 60 s with mesh traffic (a second node sending NodeInfo).
- **Expected:** the first emission is `Snapshot(nodes = …)`, later ones are `Added`, `Updated` or `Removed`, and the subscription never gets a second `Snapshot`.

#### B2: A late subscriber gets a fresh Snapshot
- **Transport:** any
- **Steps:** 30 s into B1, start a second collector on `client.nodes`.
- **Expected:** the second collector receives a `Snapshot` of the engine's current map first, not a replay of missed deltas.

#### B3: `packets` survives a slow consumer with `PacketsDropped`
- **Transport:** any (BLE saturates most easily)
- **Steps:**
  1. Subscribe to `client.packets` with a 200 ms `delay` per item.
  2. Have a second node send 200 text messages back to back.
- **Expected:** `client.events` emits one or more `PacketsDropped(Packets, n)`, with the `n` values adding up to roughly the difference between sent and received counts.

#### B4: Heartbeat keeps TCP alive
- **Transport:** TCP
- **Steps:** connect and stay idle for 20 minutes.
- **Expected:** the session stays `Connected`; logs at `VERBOSE` show `Heartbeat sent nonce=N` every 30 s.

#### B5: Heartbeat keeps serial alive
- **Transport:** Serial
- **Steps and expected:** as B4.

#### B6: BLE heartbeat opt-out works
- **Transport:** BLE
- **Steps:**
  1. Connect with the default Builder (BLE heartbeat on).
  2. Reconnect with `Builder.disableBleHeartbeat()` and stay idle 10 minutes.
- **Expected:** the first run shows heartbeats, the second none, and both stay connected.

#### B7: Liveness watchdog surfaces a silently dead link
- **Transport:** TCP (easiest to simulate)
- **Context:** the engine liveness watchdog (`MeshEngine.LIVENESS_TIMEOUT_MS = 60 s`) and the TCP read timeout (`TcpTransport.READ_TIMEOUT_MS = 65 s`) make a half-open socket (firewall state dropped, Wi-Fi access point gone, NAT entry evicted) surface as a `TransportError` within about 60 to 70 s instead of hanging.
- **Steps:**
  1. Connect over TCP, reach `Connected`, and confirm traffic flows.
  2. Without closing the session, cut the node off: pull its network, or drop its packets at the host firewall (on macOS, `sudo pfctl -E` and a `block drop from any to <NODE_IP>` rule).
  3. Watch `client.events` and `client.connection`.
- **Expected:**
  - Within about 60 s of the last `FromRadio`, one `MeshEvent.TransportError` arrives whose message contains `liveness timeout` (or `TCP read timeout` if the handshake stalled before `Connected`).
  - `client.connection` goes to `Disconnected`, or to `Reconnecting` first when `Builder.autoReconnect(...)` is on.
  - `client.connect()` succeeds once the node is reachable again.
- **Regression:** a session that sits in `Connected` for minutes after the cut means the watchdog is not running or something other than a decoded `FromRadio` resets its budget.

#### B8: `fromRadio` tap sees every envelope
- **Transport:** any
- **Steps:** collect `client.fromRadio` before `connect()` and count variants through the handshake and one minute of traffic.
- **Expected:** `my_info`, `config`, `channel`, `node_info`, `config_complete_id` and `packet` envelopes all appear; a collector with a 1 s delay per item loses the oldest envelopes but never delays `client.packets`.

### C. Send and ACK

#### C1: Local broadcast text round-trips
- **Transport:** any
- **Steps:**
  1. `client.sendText("hello")`.
  2. Observe `MessageHandle.state`.
- **Expected:** `Queued → Sent`, then `Delivered` if another node rebroadcasts it.

#### C2: Unicast text gets `Acked`
- **Transport:** any
- **Steps:**
  1. Find the second node's `NodeId`.
  2. `client.sendText("hi", to = otherNode)`.
- **Expected:** `Queued → Sent → Acked`.

#### C3: Cancel before `Sent` fails the handle as `Cancelled`
- **Transport:** serial (most predictable timing) or any
- **Steps:** `val h = client.send(packet)`, then call `h.cancel()` at once, before `Sent`.
- **Expected:** `state = Failed(Cancelled)`; the frame never reaches the transport.

#### C4: `PayloadTooLarge` is thrown synchronously
- **Steps:**
  1. Build a `MeshPacket` whose payload exceeds `DATA_PAYLOAD_LEN` (233 bytes).
  2. Call `client.send(packet)`.
- **Expected:** `MeshtasticException.PayloadTooLarge`, thrown before any handle exists.

#### C5: Disconnect mid-send resolves the handle to `Failed(Disconnected)`
- **Transport:** TCP (easiest to drop)
- **Steps:** send a unicast packet to a distant node; while it is `Sent` with no ACK yet, cut the TCP link.
- **Expected:** `state` resolves to `Failed(Disconnected)`; a coroutine awaiting the handle returns the matching outcome and does not leak.

#### C6: A second text inside two seconds fails as `RateLimited`
- **Transport:** any
- **Steps:** send two texts with `client.sendText` less than 2 s apart.
- **Expected:** the second handle resolves `Failed(RateLimited)`; a third text sent after 2 s succeeds.

### D. Admin RPCs

#### D1: `getOwner` round-trips
- **Steps:** `client.admin.getOwner()`.
- **Expected:** `AdminResult.Success(User)` with a non-empty `long_name`.

#### D2: `setOwner` then `getOwner` reflects the change
- **Steps:**
  1. `client.admin.setOwner(currentUser.copy(long_name = "Test ${Random.nextInt()}"))`.
  2. `client.admin.getOwner()`.
- **Expected:** the new `long_name`.

#### D3: `setTime` corrects clock skew
- **Steps:**
  1. Offset the host clock by 5 minutes (only on a node without GPS).
  2. Connect with `Builder.autoSyncTimeOnConnect(true)`.
- **Expected:** after the handshake, the node's time matches the host within 60 s.

#### D4: Reboot drops the transport
- **Steps:** `client.admin.reboot()`.
- **Expected:** the session ends with a transport error. With `Builder.autoReconnect(...)` on, `Reconnecting` follows and the reconnect succeeds within about 60 s.

#### D5: `editSettings` batches without intermediate reboots
- **Steps:** `client.admin.editSettings { setConfig(c1); setChannel(ch1); setOwner(u1) }`.
- **Expected:** one reboot at the end, not three.

#### D6: `SessionKeyExpired` retries once
- **Steps:**
  1. Connect, then make one admin call to a remote node through `client.admin.forNode(<PEER_NODE_ID>)` so its session is seeded.
  2. Wait more than 300 s, until `client.adminSessions` reports `NoSession` for it.
  3. Make a state-changing call to that node.
- **Expected:** the result is `Success(...)`, not `SessionKeyExpired`: the engine re-seeded the key once and replayed the call.

#### D7: `adminSessions` tracks each node's key
- **Steps:** collect `client.adminSessions` through connect and one remote admin call.
- **Expected:** the local node appears as `Active` after the handshake; the remote node appears after its first reply; each moves to `Stale` 150 s after receipt and disappears after 300 s or on disconnect.

#### D8: Remote edits leave the local cache alone
- **Steps:** with a second node in range, call `client.admin.forNode(<PEER_NODE_ID>).editSettings { setChannel(ch) }`.
- **Expected:** `client.channels` and `client.configBundle` still describe the connected node, before and after a reconnect.

#### D9: A failing `editSettings` block closes the transaction
- **Steps:** `client.admin.editSettings { setOwner(u1); error("boom") }`.
- **Expected:** the call throws the original `IllegalStateException`; the node receives `commit_edit_settings` (its log shows the transaction committed, not abandoned after 60 s); a following `editSettings` works.

#### D10: Device profile and ham mode
- **Steps:**
  1. `client.exportDeviceProfile()` and keep the result.
  2. Change the owner name and a LoRa setting, then `client.installDeviceProfile(profile)`.
  3. `client.enableHamMode("<CALL_SIGN>")`.
- **Expected:** step 2 restores the exported values in one transaction; step 3 sets the call sign while keeping the node's LoRa `tx_power` and frequency.

#### D11: File transfer round-trips
- **Steps:**
  1. `client.files.upload("/test.bin", bytes)` with 1 KB of random data, then `client.files.download("/test.bin")`.
  2. Repeat with sizes 0, 128 and 129 bytes, and over BLE.
  3. Cancel a download partway, then download the same file again.
- **Expected:** every download returns the uploaded bytes; `client.files.progress` advances; the cancelled download leaves the file on the node and the next download succeeds.

### E. Storage

#### E1: NodeDB persists across reconnect
- **Transport:** any
- **Steps:**
  1. Connect, let the NodeDB fill (at least three nodes), and disconnect.
  2. Reconnect, and subscribe to `nodes` while the session is `Configuring(Stage2)`.
- **Expected:** the late subscriber's `Snapshot` already contains the nodes from before the disconnect.

#### E2: A different node behind the same address clears storage
- **Steps:**
  1. Connect to node A at `<NODE_HOST>` and let its NodeDB fill.
  2. Disconnect, and put a factory-fresh node B at the same address.
  3. Connect.
- **Expected:** during the handshake `MeshEvent.IdentityRebound(reason = "identity_mismatch")` is emitted; after `Connected`, `nodeSnapshot()` holds only node B's NodeDB.

#### E2b: A renumbered node keeps its storage
- **Firmware:** 2.8 or later, which derives the NodeNum from the public key.
- **Steps:**
  1. Connect, let the NodeDB fill, and disconnect.
  2. Regenerate the node's key so its NodeNum changes.
  3. Connect again.
- **Expected:** `MeshEvent.IdentityRebound(reason = "node_renumbered")`; the other nodes are still present after `Connected`; the old own-node row is gone.

#### E3: A storage failure mid-session degrades, not disconnects
- **Steps:** connect with a `StorageProvider` whose writes start failing partway through the session (a custom test implementation).
- **Expected:** one `MeshEvent.StorageDegraded(reason)`; the session stays `Connected` and flows keep delivering; nothing more is written until the next `connect()`.

### F. Channels and PKI

#### F1: Channel index is reported
- **Transport:** any, with two channels configured
- **Steps:**
  1. Subscribe to `client.packets`.
  2. Have the peer send a text on channel 0, then on channel 1.
- **Expected:** both arrive, and `MeshPacket.channel` gives the index.

#### F2: A packet the node could not decrypt is dropped
- **Steps:** have a peer send on a channel the node does not have.
- **Expected:** if the node forwards the encrypted packet, it never reaches `client.packets`; `client.events` carries at most one `ProtocolWarning` about it per minute.

#### F3: PKI direct messages arrive decrypted
- **Steps:**
  1. Let the local node and the peer exchange keys (firmware-side).
  2. Have the peer send a direct message.
- **Expected:** `MeshPacket.decoded.payload` is cleartext; the SDK never sees the encrypted form, confirming the node decrypts PKI.

### G. Backpressure and lifecycle edge cases

#### G1: `disconnect()` is idempotent
- **Steps:** call `client.disconnect()` three times in a row.
- **Expected:** no exception; the state reaches `Disconnected` once.

#### G2: `connect()` while `Connected` throws `AlreadyConnected`
- **Steps:** call `connect()` again after the first connect succeeds.
- **Expected:** `MeshtasticException.AlreadyConnected` ([`error-taxonomy.md`](./error-taxonomy.md)).

#### G3: Cancelling `connect()` mid-handshake unwinds cleanly
- **Steps:** `withTimeout(2_000) { client.connect() }`, which times out before Stage 2.
- **Expected:** `TimeoutCancellationException` from `withTimeout`; `client.connection.value == Disconnected`; the transport is fully torn down (check OS sockets or file handles).

#### G4: `HandshakeTimeout` recovery
- **Transport:** TCP (easiest to silence mid-handshake)
- **Steps:**
  1. Connect; during `Configuring(Stage1)`, block the node's outbound traffic (for example `iptables -A OUTPUT -p tcp --sport 4403 -j DROP` on a Linux meshtasticd host).
  2. Wait longer than the engine's Stage 1 timeout (`STAGE1_TIMEOUT_MS`, 20 s).
  3. Restore connectivity and call `client.connect()` again.
- **Expected:**
  - The first `connect()` fails with `MeshtasticException.HandshakeTimeout` naming Stage 1, and `client.events` carries a `ProtocolWarning("handshake stage timed out")`.
  - `client.connection.value == Disconnected`.
  - The retry connects within 30 s.
  - No coroutine leak: the coroutine count returns to its baseline after `Disconnected`.
- **Repeat** for Stage 2 by blocking during `Configuring(Stage2)`.

### H. Storage migration and durability

#### H1: Migration from schema v1 drops the `messages` table
- **Transport:** any
- **Pre-req:** a database written at schema v1 (it has a `messages` table), from a checkout of an earlier tag that ran A1 once.
- **Steps:**
  1. Find the database: `<baseDir>/<dbId>.db` for `SqlDelightStorageProvider(baseDir)`; `.meshtastic-index` in the same directory maps identities to `dbId`s.
  2. Confirm the table before migrating:
     ```shell
     sqlite3 <DB_FILE> ".schema messages"
     ```
  3. Run the current SDK and connect once.
  4. Inspect again:
     ```shell
     sqlite3 <DB_FILE> ".tables"
     sqlite3 <DB_FILE> "PRAGMA user_version;"
     ```
- **Expected:** `.tables` no longer lists `messages`; `PRAGMA user_version` returns `3`; every `nodes`, `channels` and `configs` row is kept; no errors.
- **Reference:** [`1.sqm`](../storage-sqldelight/src/commonMain/sqldelight/databases/1.sqm) and [`2.sqm`](../storage-sqldelight/src/commonMain/sqldelight/databases/2.sqm).

#### H2: WAL mode and `synchronous=NORMAL` survive a crash
- **Transport:** any
- **Pre-req:** a populated NodeDB (at least three nodes).
- **Steps:**
  1. Run the SDK until the NodeDB is populated.
  2. Check the PRAGMAs (the driver applies them per connection; see ADR-014):
     ```shell
     sqlite3 <DB_FILE> "PRAGMA journal_mode;"
     sqlite3 <DB_FILE> "PRAGMA synchronous;"
     ```
     Expect `wal` and `1` (NORMAL).
  3. Kill the host process mid-write: `kill -9 <PID>` right after a storage write, or force-stop the Android app while traffic is flowing.
  4. Confirm `<DB_FILE>-wal` and `<DB_FILE>-shm` exist.
  5. Restart and read the nodes back with `sqlite3 <DB_FILE> "SELECT count(*) FROM nodes;"`, or `client.nodeSnapshot()` after connecting.
- **Expected:** SQLite recovers on the next open and checkpoints the WAL by itself; at most the one in-flight transaction is lost ([ADR-014](./decisions/014-storage-pragmas.md)); every committed node is present; no `database is locked` or `disk image is malformed` errors on the next connect.

#### H3: Identity rebind clears storage atomically
- See E2, the canonical scenario.

### I. BLE bonding and discovery

#### I1: `TransportState.Bonding` surfaces during first pairing
- **Transport:** BLE
- **Pre-req:** a node not bonded to the host (factory-reset, or removed in the OS Bluetooth settings).
- **Steps:**
  1. Watch the transport's `state` flow: `cli --json events --transport=ble:<NEEDLE> --watch` prints it in its `state` envelopes, or attach a collector in code.
  2. Call `client.connect()`.
  3. When the OS pairing prompt appears (an Android system dialog, an iOS pop-up, a macOS keychain prompt), do not accept it yet; note the transport state.
  4. Accept the prompt.
- **Expected:**
  - The state goes `Disconnected → Connecting → Bonding → Connected`.
  - During `Bonding` the engine has not started the handshake clock, so a UI can show a pairing prompt without racing `HandshakeTimeout`.
  - Dismissing the prompt or letting it time out moves the state to `Error(cause = …, recoverable = false)` and fails `client.connect()` with a transport error.
- **Reference:** [`Transport.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt), the `TransportState.Bonding` KDoc.

#### I2: An already-bonded node skips `Bonding`
- **Transport:** BLE
- **Pre-req:** the node and host from a passing I1.
- **Steps:** disconnect and reconnect in the same OS session, without re-pairing.
- **Expected:** `Disconnected → Connecting → Connected` with no `Bonding`; the handshake starts at once.

#### I3: `BleScanner.scan()` finds a node and connects through `BleTransport(radio)`
- **Transport:** BLE (Android, iOS, JVM)
- **Pre-req:** a powered node in range and advertising; the host holds the scan permissions listed in the `BleScanner` KDoc.
- **Steps:**
  1. Collect `BleScanner.scan()` for 10 s and note each `DiscoveredRadio` (`name`, `rssi`, `address`).
  2. Build `BleTransport(radio)` from the strongest result and call `client.connect()`.
  3. Cancel the scan collector and confirm the host's Bluetooth scan indicator goes off.
  4. Repeat step 1 with Bluetooth off, and on Android with the scan permission revoked.
  5. On Android, run `./gradlew :transport-ble:connectedAndroidTest` (`BleScannerDeviceTest`).
- **Expected:** the node is emitted repeatedly with a current RSSI (on BlueZ, a device known at scan start can first appear with a `null` RSSI); `Connected` within 30 s; scanning stops on cancel; with Bluetooth off or a missing permission the flow fails with `MeshtasticException.Transport`.

#### I4: `bondedBleDevices()` lists a paired node and connects without a scan
- **Transport:** BLE (Android)
- **Pre-req:** a node already bonded in Android's Bluetooth settings; `BLUETOOTH_CONNECT` granted.
- **Steps:**
  1. Call `bondedBleDevices(context)` and confirm the node is listed with its name and MAC.
  2. Build `BleTransport(device)` from it and call `client.connect()` with the node in range.
  3. Power the node off and repeat step 2.
  4. Revoke `BLUETOOTH_CONNECT` and repeat step 1; then unbond the node in settings and repeat step 1.
- **Expected:** the bonded node is listed with no scan running; `Connected` within 30 s when in range; with the node off, the connect fails with `MeshtasticException.Transport` while the list still shows it; a missing permission throws `MeshtasticException.Transport`; an unbonded node is no longer listed.

#### I5: `BleResilience` recovers a node a plain connect cannot
- **Transport:** BLE (Android; iOS and JVM must behave exactly as without it)
- **Pre-req:** a node with PIN pairing; a second node you can power off and on; `BLUETOOTH_CONNECT` granted.
- **Steps:**
  1. `bondFirst`: remove the node's bond in Android settings, build `BleTransport(address, BleResilience(bondFirst = true))`, call `client.connect()`, and enter the PIN.
  2. `bondFirst` refusal: repeat step 1 and cancel the pairing dialog.
  3. `autoConnectFallback`: bond the node, power it off, start `client.connect()`, and power it on after 20 s.
  4. `refreshStaleGattCache`: bond the node, connect once, flash firmware that changes the service table, then connect again without unpairing.
  5. Run `./gradlew :transport-ble:connectedAndroidTest` (`BleResilienceDeviceTest`).
  6. Repeat step 1 of I3 on iOS or the JVM with a non-default `BleResilience`.
- **Expected:** step 1 shows `Connecting → Bonding → Connected`; step 2 fails with `MeshtasticException.Transport` before any GATT connect; step 3 connects once the node returns, with `autoConnect = true` in the platform log; step 4 connects with the node's current service table; step 6 behaves as with `BleResilience.None`.

#### I6: `BleTransport.deviceLogs()` streams device logs without stalling the link
- **Transport:** BLE (Android, iOS, JVM)
- **Pre-req:** a connected node with `security.debug_log_api_enabled` on (set through admin config); a second run with it off.
- **Steps:**
  1. Collect `transport.deviceLogs()` while `Connected` and print each `LogRecord` (`level`, `source`, `message`).
  2. Add a 2 s `delay` per record, send 20 text messages, and confirm the sends complete.
  3. Cancel the collector and confirm no more records arrive.
  4. Disconnect, reconnect and collect again.
  5. Turn `debug_log_api_enabled` off and repeat step 1.
  6. On Android, run `./gradlew :transport-ble:connectedAndroidTest` (`BleDeviceLogsDeviceTest`).
- **Expected:** records arrive in order with plausible levels; with the slow collector some records are skipped but the sends and the link are unaffected; cancel stops delivery; step 4 delivers again; step 5 delivers nothing and does not fail.

## Recording results

For each release, copy this template into `docs/release-history/vX.Y.Z-conformance.md` and fill it in:

```markdown
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
- A5 (stale-frame discard): SKIPPED (cannot provoke)

## Steady state
...
```

File a GitHub issue for every failure, with the test ID and the relevant logs. The ABI freeze needs a clean pass of sections A, B and C; D to I are expected before 1.0.

## Hardware-in-the-loop CI

A self-hosted runner with a fixed pair of nodes on a USB hub is planned after 1.0, running A1, A3, A4, B4, B5, C1, C2, D1, D2, D4 and E1 nightly. Until it exists, this page is the manual replacement.

## Related

- [`api-reference.md`](./api-reference.md): what each test exercises
- [`architecture/handshake-fsm.md`](./architecture/handshake-fsm.md): background for A1, A4 and A5
- [`error-taxonomy.md`](./error-taxonomy.md): expectations for D6 and G2
- `samples/cli/README.md`: CLI flags used in the commands
