# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [0.3.1] - 2026-11-27

### Fixed

- `BleScanner.scan()` builds the platform scanner when the flow is collected, so a scanner that
  cannot be built fails the flow with `MeshtasticException.Transport` instead of throwing from
  `scan()` unwrapped.
- `JvmSerialPorts.open` fails with `MeshtasticException.Transport` for a port that does not
  exist, as `AndroidSerialPorts.open` does, instead of jSerialComm's
  `SerialPortInvalidPortException`. That is on Linux and macOS; on Windows jSerialComm does not
  check the name, and a missing port fails at `connect()` with the same exception.

## [0.3.0] - 2026-11-27

### Breaking

- `SendFailure.RateLimited` is a new case, and a send the device refuses with
  `Routing.Error.RATE_LIMIT_EXCEEDED` now fails with it instead of
  `SendFailure.Other(RATE_LIMIT_EXCEEDED)`. Firmware answers that way to a second text message
  inside two seconds. An exhaustive `when` over `SendFailure` needs the new branch, and code
  matching `Other` for this error no longer sees it. Admin calls still return
  `AdminResult.RateLimited`.
- `MeshEvent.TransportError.error` is a `MeshtasticException`, no longer narrowed to
  `MeshtasticException.Transport`. A `HandshakeTimeout` or `StorageUnavailable` that ends a
  session now arrives as its own type instead of a `Transport` wrapper whose message had to be
  parsed. Source-compatible for code that reads `error.message`; binary-incompatible.
- `InMemoryStorage` and `InMemoryStorageProvider` moved from `:testing`
  (`org.meshtastic.sdk.testing`) to `:core` (`org.meshtastic.sdk`). `:testing` keeps deprecated
  typealiases, so sources compile; binaries built against the old classes do not link.
- `AutoReconnectConfig` gained a seventh constructor parameter, `recoveryStep`. Named and
  default-argument call sites are unaffected; binaries built against the six-parameter
  constructor do not link.
- Published JVM and Android bytecode is JDK 17 (class file 61) and compiled with
  `-Xjdk-release=17`, as the README and constitution already stated; 0.2.0 shipped JDK 21
  bytecode. JDK 17 hosts can now load the artifacts.
- `ConnectionState.DeviceSleeping(since, expectedWakeSeconds)` is a new case. A BLE link that a
  power-saving device drops by design, after a fully handshaken session, is reported as
  `DeviceSleeping` instead of `Reconnecting` (auto-reconnect on) or `Disconnected` (off). An
  exhaustive `when` over `ConnectionState` needs the new branch.
- Admin calls that fail with `NO_ROUTE`, `GOT_NAK` or `MAX_RETRANSMIT` return
  `AdminResult.Failed(error)` with that `Routing.Error` instead of `AdminResult.NodeUnreachable`,
  which now means the link was lost. Code matching `NodeUnreachable` for routing failures must
  match `Failed` as well.
- `RadioClient.Builder` reconnects by default. Pass `autoReconnect(AutoReconnectConfig.Disabled)` to
  keep host-owned reconnect.
- `ConfigBundle` gained a sixth constructor parameter, `regionPresets`, and `DeviceCapabilities`
  a second, `excludedModules`. Named and default-argument call sites are unaffected; binaries
  built against the old constructors, `copy` or `componentN` do not link.
- `AdminApi` gained `setDisplayMirror` and `requestDisplayFrame`; a class that implements
  `AdminApi` must add them.
- The engine collects `RadioTransport.frames()` once per successful `connect()`, including each
  auto-reconnect, instead of once per engine. A custom transport that reconnects must return a
  flow for the current link on every call; one shared `consumeAsFlow()` fails on the second
  link. The bundled transports already do.
- Built with Kotlin 2.4.21. On the JVM, constructors that take a value class (`NodeId` and others) gained a
  `BoxingConstructorMarker` in their synthetic signatures, so a consumer compiled against an earlier release must
  recompile; source is unchanged.
- `DroppedFlow` gains `Nodes`. A node change dropped by a stalled `nodes` subscriber is reported as
  `MeshEvent.PacketsDropped(DroppedFlow.Nodes, …)` instead of `DroppedFlow.Events`. An exhaustive
  `when` over `DroppedFlow` needs the new case.
- `RoutingApi` gained `requestPosition` and `requestNodeInfo`; a class that implements it must add
  them.
- `MeshEvent.SecurityWarning.PublicKeyMismatch` is a new case, so an exhaustive `when` over
  `SecurityWarning` needs a branch.
- `KeyVerificationPrompt` is a sealed interface with three concrete prompts
  (`EnterSecurityNumber`, `ShowSecurityNumber`, `ConfirmVerification`) instead of an empty
  marker interface. An external implementer no longer compiles; a collector that only
  `is`-checks it is unaffected.
- `sendDirectMessageEncrypted` is `suspend`, because it reads the recipient's key from the
  node DB. Callers outside a coroutine need a scope.
- `MeshEvent.RoutingReport` is a new case, so an exhaustive `when` over `MeshEvent` needs a branch.
- `RoutingApi.traceRoute(dest, hopLimit, channel, requestId)`: `hopLimit` is nullable and defaults
  to the device's `lora.hop_limit`; `DEFAULT_HOP_LIMIT` stays. `requestNeighborInfo` gains
  `channel` and `requestId`. Both accept a caller-chosen packet id. A class implementing
  `RoutingApi` must match the new signatures; `FakeRadioTransport.injectRouteReply` now sends the
  reply on `TRACEROUTE_APP`.
- `RoutingApi` and `TelemetryApi` gained `withRpcTimeout`; a class that implements either must add it.
- `ConnectionState.Failed(cause)` is a new case: the engine reports it, not `Disconnected` or
  `Reconnecting`, for a non-recoverable transport error, `FirmwareTooOld`, and an exhausted
  `maxAttempts` or `maxHandshakeRecoveries`, and stays there until `connect()` or `disconnect()`.
  An exhaustive `when` over `ConnectionState` needs the new branch.
- `ConnectionState.Disconnecting` is a new case, published by `disconnect()` from any state but
  `Disconnected` while the engine tears the session down, then `Disconnected`. A link loss never
  shows it. An exhaustive `when` over `ConnectionState` needs the new branch.
- `AutoReconnectConfig.recoveryStep` and `ReconnectRecoveryStep` are removed before release; pass a
  `RadioTransportProvider` to `RadioClient.Builder.transport(identity, provider)` instead, which
  can also return a fresh transport per attempt.
- The Android `BleTransport(address | radio, resilience, refreshGattCacheOnConnect, builderAction)`
  factories are removed before release; call `BleTransport.refreshGattCacheOnNextConnect()` after a
  firmware update instead.
- With auto-reconnect on, a failed first attempt, a handshake timeout, the liveness watchdog, a
  handshake protocol error and `FromRadio.rebooted` are retried instead of ending the session, so
  `connect()` returns at the first `Connected` and throws only with the cause of `Failed`.
- With auto-reconnect off, a session drop no longer passes through `Reconnecting(cause, 1)` on its
  way to `Disconnected`.
- `RadioClient.Builder.sendTimeout` is renamed `sendAckTimeout`; it bounds only the wait for a routing ACK.
- `RadioClient.sessionEvents` is now a non-null `Flow<RadioSessionEvent>`; collecting it without
  `Builder.sessionReceive()` fails with `IllegalStateException`. `RadioSessionEvent.Ended` now also
  closes a session that stopped at `Lockdown`. `deviceLogs`, `outboundPackets` and `displayFrames`
  emit `SessionScoped<T>`, which carries the `sessionId` of the session each item belongs to.
- A send a peer refuses with `Routing.Error.GOT_NAK` now fails with `SendFailure.Other(GOT_NAK)`
  instead of `SendFailure.NoRoute`, and a setter acked with `GOT_NAK` returns
  `AdminResult.Failed(GOT_NAK)` instead of `Failed(NO_ROUTE)`.

### Added

- mDNS discovery in `:transport-tcp`: `TcpDiscovery.discover()` is a cold `Flow<TcpDiscoveryEvent>`
  (`Found` / `Lost`) of radios advertising `_meshtastic._tcp`, each a `DiscoveredTcpRadio` with
  its addresses, port and TXT attributes and `toTransport()` / `toTransportSpec()` for
  connecting. Implementations are `JvmTcpDiscovery` (JmDNS 3.6.5, Apache-2.0, a new JVM-only
  dependency of `:transport-tcp`), `AndroidTcpDiscovery(context)` (`NsdManager`) and
  `IosTcpDiscovery` (`NSNetServiceBrowser`). The iOS implementation compiles on every iOS
  target but has not been run on a device or simulator.
- Browser target for `:storage-sqldelight`: `SqlDelightStorageProvider(baseDir, createWorker)` keeps
  each radio's database in SQLite on the Origin Private File System, through a worker shipped
  in the klib (`sqlite-opfs.worker.mjs`). Storage calls are now serialized on every target.
- `AutoReconnectConfig.recoveryStep` and `ReconnectRecoveryStep`: host work the supervisor runs
  after the backoff delay and before each `transport.connect()`, such as re-requesting a USB
  permission or power-cycling a radio. Throwing counts as a failed attempt.
- `AndroidUsbSerialPorts` in `:transport-serial`: an Android serial backend on
  usb-serial-for-android, the driver stack Meshtastic-Android ships, with its probe table for
  the RAK4631, T-Beam v1.1 and nRF52840 CDC boards. The library is `compileOnly` because it is
  published on JitPack, so a host adds it and the repository itself.
- `SerialFrameAssembler` is public (`org.meshtastic.sdk.transport.serial`) so a host with its
  own byte stream can build a `RadioTransport` on the same framing.
- `SerialFrameAssembler.onTextLine` and `open(..., onTextLine)` overloads on `JvmSerialPorts`,
  `AndroidSerialPorts` and `AndroidUsbSerialPorts` deliver each line of text a radio prints
  between frames, which carries the firmware's serial debug log.
- `InMemoryStorageProvider` in `:core`, usable in production without `:testing`'s turbine and
  coroutines-test dependencies.
- Typed `MeshPacket` payload accessors for more portnums: `asAlertText`, `asRangeTestText`,
  `asDetectionSensorText`, `asPaxcount`, `asRemoteHardware`, `asNodeStatus`, `asMapReport`,
  `asMeshBeacon`, `asRemoteShell`, `asPowerStress`, `asKeyVerification` and `asAtakPacket`.
  Each returns `null` for another portnum or a payload that fails to decode.
- Kotlin/JS target for `:core` and `:testing`: `org.meshtastic:sdk-core-js` and
  `org.meshtastic:sdk-testing-js` klibs, added through the `meshtastic.js.library` convention
  plugin. The common engine, codecs and fakes compile unchanged; `commonTest` runs under
  Node.js (`./gradlew jsNodeTest`). No transport ships for JS yet, so a consumer supplies its
  own `RadioTransport`. Existing targets' API dumps are unchanged apart from the klib target list.
- `DeviceStorage.recordOwnNode(nodeNum, firmwareVersion, deviceId)` and
  `DeviceStorage.loadOwnDeviceId()`, both with default bodies, so existing implementations still
  compile and keep clearing on every NodeNum change. `InMemoryStorage` and `:storage-sqldelight`
  override them. `FakeRadioTransport.deviceId` sets the `MyNodeInfo.device_id` it reports.
- `BleScanner.scan()` and `DiscoveredRadio` in `:transport-ble`: a cold `Flow` of Meshtastic radios
  found by the mesh service UUID, on Android, iOS and JVM, with no Kable types in the signature.
  `BleTransport(radio)` connects to a result on every target. The caller holds the BLE
  permissions; a scan that cannot start fails with `MeshtasticException.Transport`.
- `LoRaRegionInfo`, `ModemParams`, `ModemPreset.modemParams` and `LoRaFrequency`: firmware's
  region table, modem preset parameters and frequency slot math as pure functions.
  `LoRaFrequency.plan(config, primaryChannelName)` returns the region, bandwidth, slot count,
  default slot and center frequency in MHz a `Config.LoRaConfig` resolves to, matching
  `RadioInterface.cpp` in single precision.
- `RadioClient.enableHamMode(callSign, ...)`: sets ham mode and fills `tx_power` and
  `frequency` from the loaded LoRa config, because firmware applies both from the request
  unconditionally. `AdminApi.setHamMode` still sends its `HamParameters` verbatim; its KDoc now
  says 0 resets TX power and clears `override_frequency`.
- `RadioClient.exportDeviceProfile` and `RadioClient.installDeviceProfile`: export the device's
  settings as a `DeviceProfile` and install one through a single `editSettings` transaction,
  writing only the sections the profile has. The private key is left out of exports and the
  security section is skipped on install unless the caller opts in.
  The ringtone and canned-message reads are skipped for modules the device's metadata excludes and
  otherwise wait at most 3 s, concurrently, since firmware does not answer without the module.
- Reconnect attempts made while a device is `DeviceSleeping` do not count against
  `AutoReconnectConfig.maxAttempts` until its wake bound plus twice `maxBackoff` has passed; after
  that the state is `Reconnecting`. The bound is the tracker's position interval, the sensor's
  environment interval or `ls_secs` for light sleep.
- `RadioClient.files` (`FileTransferApi`): read a file from the device (`download`) and write
  one to it (`upload`) over firmware's XModem exchange, with `progress`, one transfer at a time
  (`FileTransferFailure.Busy`), a 1 MiB cap, per-block timeout and retries, and typed
  `FileTransferResult` failures. File transfer only; firmware flashing is not covered.
- Per-packet provenance accessors on `MeshPacket`: `transport`, `relayNode` and `nextHop` (a
  `NodeLastByte`, one byte that several nodes can share, with `candidates(nodes)`), `signature`
  and `xeddsaSignature` (what the device verified, not an SDK check), and `ackProof`. `NodeInfo`
  gains `heardOnCurrentLora` and `signsPackets`.
- `MeshPacket.storeForwardOriginalId()` and `Flow<MeshPacket>.dropDuplicateStoreForwardReplays()`:
  drop store-and-forward text replays already delivered, keyed on the sender and
  `StoreAndForward.original_id`, over a bounded window.
- `RadioClient.adminSessions` and `adminSessionStatus(node)`, with `AdminSessionStatus`: whether
  an admin session with a node is `Active`, `Stale` or absent (`NoSession`). Firmware accepts a
  session passkey for 300 s from generation and rotates it after 150 s, so a key received in a
  response is expected to work for 150 s (`Active`, not a guarantee) and may work until 300 s
  (`Stale`). Cleared on `ADMIN_BAD_SESSION_KEY`,
  disconnect and identity rebind. The engine now keeps a passkey for 300 s instead of 4 minutes.
- `bondedBleDevices(context)` and `BondedBleDevice` in `:transport-ble` (Android only): the
  devices the host has bonded with, listed without a scan. A bond is not presence, and the list
  is every bonded device, not only radios. `BleTransport(device)` builds a transport from one.
  The caller holds `BLUETOOTH_CONNECT`.
- `BleResilience` in `:transport-ble`, an opt-in argument to `BleTransport(radio, ...)` and to
  the Android `BleTransport(address, resilience)` and `BleTransport(device, resilience)`: bond
  before the GATT connect (`bondFirst`), retry a stalled direct connect with `autoConnect`
  (`autoConnectFallback`), and refresh Android's cached GATT table once when discovery lacks the
  Meshtastic service (`refreshStaleGattCache`). All default to off and are ignored on iOS and JVM.
- `BleTransport.deviceLogs()` and `BleConstants.LOGRADIO_UUID` / `LOGRADIO` in `:transport-ble`:
  a cold, opt-in `Flow<LogRecord>` from the radio's `logradio` characteristic, buffered with
  drop-oldest so logs never slow the link. The device must have `security.debug_log_api_enabled`
  on. Independent of the `FromRadio.log_record` path.
- `TransportTarget.parse` reads the compact `ble:NEEDLE`, `tcp:HOST[:PORT]` and
  `serial:PORT[:BAUD]` specs a CLI flag or tool argument carries, and returns `null` for a
  malformed one. `bleAdvertisementMatches` matches a BLE needle, including a MAC against a BlueZ
  object path. `JvmSerialPorts.findMeshtastic()` and `byIdPath()` find serial radios by USB
  vendor ID and their stable `/dev/serial/by-id/` path.
- `Builder.protocolLogging` now logs every `FromRadio` and `ToRadio` envelope (tag `Protocol`).
  `PayloadRedactor.Default` prints the variant, routing metadata, queue status counters,
  heartbeat nonce and XModem control, sequence and buffer length, never payload content.
- `:transport-websocket` with `WebSocketTransport`: connects to firmware's WebSocket phone API at
  `ws://host[:port]/api/v1/ws` or `wss://`, one protobuf per binary message, on Ktor (CIO on JVM
  and Android, Darwin on iOS). It stays under the node's 15 minute idle close on the engine's
  heartbeat and sends no pings, and reports close 4001 (another client took the slot) and 1009
  as non-recoverable errors. Messages over 512 bytes end the session. ESP32 needs firmware#12412 and meshtasticd needs #12413 (plain
  `ws://` needs #12414's `HTTPPort`), all unmerged.
- `AdminApi.lockdown` and `MeshEvent.LockdownStatusChanged` document the lockdown fields protobufs
  2.8.1 carries: `LockdownAuth.disable` turns lockdown off on a provisioned device,
  `LockdownAuth.max_session_seconds` bounds the issued session, and `LockdownStatus.State.DISABLED`
  reports a lockdown-capable build with lockdown not active.
- `AdminApi.withRpcTimeout(timeout)` returns an admin API whose calls wait up to `timeout`, for
  multi-hop remote reads.
- `RadioTransport.linkDropsOnRestartCommands`, true for `BleTransport`, marks links that firmware
  takes down inside restart-class admin handlers.
- The Android BLE transport refreshes its GATT cache before the PhoneAPI handshake of the next
  connect that reaches the radio after `BleTransport.refreshGattCacheOnNextConnect()`, for use after
  a firmware update.
- Shutting down a BLE transport created by an SDK factory releases the factory's Kable peripheral.
- `cli mcp` serves MCP over stdio from one long-lived radio session, so an MCP client
  reaches a radio over BLE, TCP or serial without reconnecting for each call.
- `ConfigBundle.regionPresets`: the device's own legal modem presets and default preset per
  region (`FromRadio.region_presets`, sent in Stage 1, so config-only sessions get it too).
  `null` on firmware that never sends it. Session-only: re-sent on every connect, not stored.
- `DeviceCapabilities.hasModule(ExcludedModules)` and `ConfigBundle.capabilities`: whether the
  device has a module compiled in, from `DeviceMetadata.excluded_modules`. A device that
  reports no bitfield has every module.
- `RadioClient.deviceLogs`: the device's `log_record` frames as a `Flow<LogRecord>`, handshake
  included. Firmware sends them on serial and TCP when `security.debug_log_api_enabled` is set;
  the SDK does not change that setting. Drop-oldest at 64, no replay, so a slow collector never
  slows the engine and the logs never reach `events`.
- Device display mirroring: `AdminApi.setDisplayMirror` and `AdminApi.requestDisplayFrame`
  (local device only), `RadioClient.displayFrames` with reassembled `DisplayMirrorFrame`s, and
  `RadioClient.displayPalette`. Frames with a gap or over 1 MiB are dropped, and a slow collector
  loses the oldest frame.
- `AdminEdit.setFixedPosition` and `AdminEdit.removeFixedPosition` write a fixed position inside
  an `editSettings` or `batch` transaction.
- `FakeRadioTransport(autoQueueStatus = true)` answers every outbound packet with an accepting
  `QueueStatus`, as firmware does.
- `RadioClient.channelFor(node)`, `sendDirectMessageOnHeardChannel` and
  `requestPositionOnHeardChannel`: send on the channel the node was last heard on, which
  `sendDirectMessage` and `requestPosition` do not do with their default channel `0`.
- Request a nodes-only download on an established connection and receive the complete batch in
  the ordered session stream.
- `TelemetryApi.requestLocalStats(node)` asks a remote node for its `LocalStats`; firmware answers.
- The send builder takes `priority(MeshPacket.Priority)`.
- `RoutingApi.requestPosition(node, channel, position, requestId)` and
  `RoutingApi.requestNodeInfo(node, channel, requestId)` send the requests of
  `RadioClient.requestPosition` and `RadioClient.requestNodeInfo` and return the reply as
  `AdminResult<Position>` and `AdminResult<User>`, matched by `request_id`, port and sender.
  Firmware leaves some requests unanswered, so `Timeout` is expected there; the KDoc lists the cases.
- An acknowledged `toggleMuted`, `setFixedPosition` or `removeFixedPosition` to the connected node
  updates the SDK node map and config as firmware does, and appears on the session stream.
- `AdminApi.nodeDbReset(preserveFavorites = false)` wipes favorites too; `nodedb_reset` is a
  oneof member, so `false` reaches firmware.
- `RadioClient.outboundPackets` emits each `MeshPacket` the client queues for the radio, as written,
  with the id, `from`, session passkey and PKI fields the SDK filled in, so a host can log what it
  sent. It covers sends, paced sends, RPC requests and admin writes, never lockdown credentials.
- `MeshEvent.SecurityWarning.PublicKeyMismatch(node, offeredKey)`: a `NODEINFO_APP` offering a
  key different from the stored 32-byte key is still dropped, and is now reported once per
  node and offered key.
- `MeshEvent.KeyVerification` is emitted for the three `ClientNotification` key-verification
  payloads, next to the raw `MeshEvent.Notification`. With `AdminApi.keyVerification` a host
  can run the whole exchange.
- `MeshEvent.RoutingReport` reports each routing ACK or NAK addressed to us, for any packet id,
  including one sent in an earlier session or process: the node that sent it, the
  `Routing.Error`, whether the local radio produced it, the relay's node byte and the
  `ack_proof_status`. `isImplicitAckFor(destination)` tells an overheard relay from a delivery.
  It arrives on `events` and, after its packet, on `sessionEvents`.
- `RoutingApi.withRpcTimeout` and `TelemetryApi.withRpcTimeout`, matching `AdminApi.withRpcTimeout`, for a
  multi-hop request whose reply or routing error can outlive the client's `rpcTimeout`.
- `RadioTransportProvider` and `RadioClient.Builder.transport(identity, provider)`: auto-reconnect
  asks the provider for the transport of each attempt, after disconnecting the previous one. It
  can return the same instance or a fresh one. The attempt is counted before `open()` runs: waiting
  in `open()` until a connection is possible uses up no further attempt, while throwing from it
  fails that attempt as a failed `connect()` would.
- `AutoReconnectConfig.stableAfter`: the attempt count and backoff start over only after a session
  stays connected that long, so a radio that drops right after its handshake keeps backing off.
- `AutoReconnectConfig.maxHandshakeRecoveries` bounds handshake, liveness, protocol and receive
  overflow restarts in a row.
- `ConnectionState.Reconnecting.retryAt` says when the next attempt starts, and
  `RadioClient.retryNow()` starts it at once, from `Reconnecting` or `DeviceSleeping`.
- `BleResilience.refreshGattCacheAfterFailures` refreshes Android's GATT cache once per streak of
  that many failed connects or links that dropped within 5 s; a link that stays up re-arms it.
- `SendBuilder` sets the fields a durable outbox needs: `id(MessageId)` sends under a caller-chosen
  packet id, `replyTo(MessageId)` sets `Data.reply_id`, `reaction()` sets `Data.emoji`, and
  `pkiEncrypted()` sends a PKI direct message on channel 0 with the recipient key from the node map
  (`pkiEncrypted(publicKey)` pins one). `RadioClient.nextPacketId()` draws an id before the send.
- `RadioClient.sendText` takes `wantAck` (default `true`), as `sendDirectMessage` does. With it
  off the packet carries the device hop limit. The previous signature stays, hidden, so compiled
  callers keep linking.
- `AdminApi.playRingtone()`, an acked write of `play_ringtone_request`.
- `RadioClient.requestNodeInfo(node, channel, user)` and `requestNodeInfoOnHeardChannel(node)`
  send our own `User` with the request and no `want_ack`, as the Android app does.
- `requestPosition(from, channel, position)` and `requestPositionOnHeardChannel(from, position)`
  share our position with the request and stamp `time` with the client clock.
- `RadioClient.requestPaxcount(node)` asks for a pax counter reading on the heard channel.
- `StoreForwardApi.requestLocalHistory(lastRequest, windowMinutes, maxMessages)` asks the
  connected node, as its own S&F server, to replay history.
- `RadioClient.remoteShell` (`RemoteShellApi`) sends PKI-encrypted `REMOTE_SHELL_APP` frames,
  decodes a node's inbound frames and mints session IDs.
- `RadioClient.mqttProxy` (`MqttProxyApi`) streams the device's MQTT proxy messages and
  delivers broker messages back to it.
- `RadioClient.sendPaced { … }`, the paced counterpart of the send DSL.
- `RadioClient.Builder.sessionReceive()` enables an ordered, bounded stream of handshake
  snapshots and session-tagged packets for one app consumer. If the SDK queue overflows, the
  stream drains accepted events and reports the affected session while the radio stays online.
  On reconnect, the SDK starts a reader for the new transport connection and discards work
  queued by the previous reader.
- After `Ready` the stream carries typed `NodeChanged`, `ConfigChanged`, `ChannelsChanged` and
  `Event` in order with packets, so an app can project the session into its own store without
  decoding envelopes. Acknowledged local admin writes appear there once the call returns.
- Hosts can check whether a raw command was admitted to the transport send queue.
- `SendFailure.routingError` returns the `Routing.Error` behind a failure, and
  `SendFailure.QueueRejected.routingError` types the queue receipt, null for values 32 to 35, which are
  ambiguous between firmware `ERRNO_*` enqueue results and `Routing.Error` refusals.
- `AndroidUsbSerialPorts.prober` is public, so a host that scans USB itself shares the SDK's driver
  table.
- `wasmJs` target on `sdk-core`, `sdk-testing` and `sdk-transport-websocket`, next to `js`, for Kotlin/Wasm hosts such as Compose Multiplatform for web. The websocket transport uses Ktor's JS engine over the platform `WebSocket` on both, and tests run on Node.js.
- Waypoint rules and senders: `Waypoint.isExpired(now)`, `Waypoint.canBeEditedBy(node)`,
  `newWaypointId()`, `RadioClient.sendWaypoint` and `RadioClient.deleteWaypoint` (a re-send with
  `expire = 1`, as firmware and the Android app delete). The SDK keeps no waypoint store.
- Use the SDK BLE transport from JavaScript and Wasm browsers with Kable Web Bluetooth.
- Kotlin/JS target for `:transport-websocket` (`org.meshtastic:sdk-transport-websocket-js`), so a
  browser client can use `WebSocketTransport` over the platform `WebSocket` through Ktor's JS
  engine. TLS trust and mixed-content rules are the browser's. `commonTest` runs under Node.js.
- Allow WebSocket URLs with query parameters and keep the query out of the transport identity.
- `WebSocketTransportException`, a `MeshtasticException.Transport` that `WebSocketTransport`
  throws from `connect()` and publishes as the cause of a session-ending `TransportState.Error`.
  It carries `closeCode`, `closeReason`, `httpStatus` for a refused upgrade and `slotContested`,
  so callers no longer parse the message. `MeshtasticException.Transport` is now open so a
  transport can subclass it.

### Changed

- `org.meshtastic:protobufs` 2.8.2, which only adds fields, so the SDK's API is unchanged.
- kotlinx-coroutines is compiled against 1.8.1, the floor a host can run the SDK on, down from
  1.11.0. Bytecode built against 1.9+ calls `Job.cancel$default` and `SendChannel.close$default`
  statics that 1.8.x does not have, so on a 1.8.1 runtime `:core` failed at 351 call sites;
  the SDK's own suite now compiles and passes against 1.8.1. Consumers on a newer pin keep
  it: Gradle resolves the highest version.
- Editing another node with `forNode` preserves the connected radio's cached channels,
  config and module config for `setChannel`, `editSettings` and `batch`.
- `:storage-sqldelight` runs its SQLite work on a fixed four-thread pool instead of
  `Dispatchers.IO.limitedParallelism(4)`, which compiles to a `$default` call 1.8.x lacks, and
  no longer depends on `sqldelight:coroutines-extensions`, which it never used and which pulled
  coroutines 1.11 into the storage POM.
- Acked admin writes, including those inside `editSettings` and `batch`, go through the paced send
  queue, one in flight until its QueueStatus, so a burst no longer overruns the device's
  three-packet BLE inbound queue.
- A PR records its changelog entry as a `changelog.d/<name>.<section>.md` fragment instead of
  editing `CHANGELOG.md`, so concurrent PRs no longer conflict. `scripts/changelog.sh cut` folds
  the fragments into `## [Unreleased]` before it cuts, and `fold`, `preview` and `check` are new.
- `FakeRadioTransport.nodeNum` is settable, so a test can model a node that renumbers: the
  auto-handshake reports the current value.
- `FakeRadioTransport` with `autoHandshake` answers only the first `get_owner_request` of each
  `connect()`, the engine's passkey seed, and sets the reply's `request_id` to the request's packet
  id as firmware does. Later `getOwner()` calls are left for the test to answer.
- `connect()` throws `MeshtasticException.FirmwareTooOld` when the device reports firmware below
  2.3.15 (`DeviceVersion.ABS_MIN_SUPPORTED`), instead of continuing into a handshake it may
  not complete. Auto-reconnect does not retry it. Firmware from 2.3.15 up to 2.5.13 (below
  `DeviceVersion.MIN_SUPPORTED`, 2.5.14) still connects and emits one
  `MeshEvent.ProtocolWarning` per session recommending an update. A missing or unparseable
  version still connects.
- The handshake sends `want_config_id = 69420` once per session instead of again after 10 s
  without `my_info`: firmware's `handleStartConfig` has no re-entry guard, and the Android app
  dropped the same resend after it crashed a T-Beam on 2.7.25. A silent Stage 1 still ends in
  `HandshakeTimeout` at 20 s.
- Stage 2 fails only after a 30 s progress window with no frame, or after 600 s in total, instead
  of after 60 s in total, so a large NodeDB over slow BLE completes. 600 s matches the Android
  app's ceiling (10 times its 60 s Stage 2 budget). `connectAndAwaitReady()` still gives up after
  its own `timeout`, 30 s by default, so a caller expecting a long NodeDB download raises it.
- A local `get_owner_request` that gets no reply within 10 s no longer fails `connect()` with
  `HandshakeTimeout("Seeding Session")`: the session goes `Connected` without the local passkey and
  emits `ProtocolWarning("session passkey seed timed out")`. Local admin writes need no passkey.
- `autoSyncTimeOnConnect` sends its `set_time_only` as soon as the Stage 1 `my_info` frame
  arrives, instead of an `AdminApi.setTime()` after `connect()` returns, so firmware can re-date
  packets it queued without a clock while they are still queued. On a fast TCP link firmware can
  flush them before it reads the sync.
- Kable 0.46.0. Android requests the 517-byte ATT MTU through Kable's connect-time `PeripheralBuilder.mtu`, and the
  stale GATT cache refresh uses Kable's `refreshGattCache` instead of reflection, so the AAR no longer ships keep rules
  for Kable internals.
- `requestPosition` sets `BACKGROUND` priority on the request, as the Android app does, instead
  of the default priority.
- `WebSocketTransport.disconnect()` keeps the count of quick drops when the session had already
  ended with an error, as it has when the engine disconnects before reconnecting, so a contested
  slot is reported while auto-reconnect runs. Disconnecting a live session still resets it.
- With auto-reconnect on, a `sessionEvents` overflow while a collector runs ends the session and
  reconnects, counted against `AutoReconnectConfig.maxHandshakeRecoveries` (three in a row when it
  is `null`), and a collector that got the
  `RadioSessionOverflowException` collects `sessionEvents` again to resume: the new collection
  starts with `Ended` for the overflowed session, then the next `Ready`. A session that reached
  `Connected` resets the restart count when it ends, unless it ended in an overflow. With
  auto-reconnect off the stream stays failed, as before.
- `RadioClient.Builder.sessionReceive()` defaults to 512 events instead of 256, since a received
  packet can bring a node change with it.
- `Builder.sessionReceive()` defaults to 4096 events instead of 512, enough for the stored-history
  replay of a 250-node radio while the collector installs the `Ready` snapshot. The settle and
  handshake buffers hold 128 frames instead of 64, a full meshtasticd to-phone queue (100).
- Stage 1 fails only after 20 s with no frame once the dump has started, with a 200 s cap, instead
  of 20 s in total, so a slow BLE config dump completes. A silent Stage 1 still times out at 20 s.
- `:transport-ble` exposes Kable as an `api` dependency, since its public API uses Kable types, so a
  consumer no longer adds `kable-core` to compile against `BleTransport`.
- `WebSocketTransport` stops reporting a recoverable error after five sessions in a row that open and
  end without a close frame within 30 seconds, since another client is repeatedly taking the node's
  only WebSocket slot. The next `connect()` after a 1013 waits until 30 seconds after that session
  ended, and on JVM, Android and iOS the SDK-created client times out a connect after 30 seconds.

### Fixed

- Live mesh traffic now updates the node DB. Every decoded packet creates or refreshes its
  sender (`last_heard`, `snr`, `hops_away`, `via_mqtt`), `NODEINFO_APP` merges the sender's
  `user` and `POSITION_APP` its `position`, each emitting one `NodeChange.Added` or
  `NodeChange.Updated`. Before, peers only changed through `FromRadio.node_info` and device
  metrics from already-known nodes.
- A `null` hop limit on `send(portnum, …)` and the `send { }` DSL sends the device's
  `lora.hop_limit` (or the firmware default, 3) instead of `0`. The firmware only substitutes
  its default for `0` when `want_ack` is set, so with `want_ack` off those packets went out
  with zero hops and nothing relayed them.
- `AdminApi.setConfig` and `setModuleConfig` update `configBundle` once the device acks the
  write, as `editSettings` already did. Remote-node writes leave the local bundle alone.
- Keep-alive heartbeats carry a nonce that counts up from 2 instead of a constant `0`. Firmware's
  BLE write handlers drop a ToRadio write byte-identical to the previous one, so consecutive
  nonce-0 heartbeats on an idle link were silently dropped. The nonce never takes `0` or `1`
  (`1` makes firmware broadcast NodeInfo) and wraps back to `2`.
- A device that comes back under a new `my_node_num` with the same `MyNodeInfo.device_id` (firmware
  2.8 derives the NodeNum from the public key, so an upgrade or key regeneration renumbers it)
  keeps its persisted nodes, channels and config. `MeshEvent.IdentityRebound` fires with
  `reason = "node_renumbered"` and only the old own-node row is dropped. Any other NodeNum change
  still clears storage with `"identity_mismatch"`. Databases written before this release hold no
  device id, so the first 2.8 connect after upgrading the SDK still clears.
- `HistoryReplayComplete.delivered` counts a store-and-forward text once per sender and
  `original_id`. A server replays each message under a fresh packet id, so a repeated replay was
  counted again and could end the replay early.
- `editSettings` and `batch` close the settings transaction the device accepted when their
  block throws or is cancelled, keep the original failure, and never commit on a connection
  that replaced the one `begin_edit_settings` went out on.
- An acknowledged local admin write no longer lands on a session that replaced its link before the
  call returned, such as the one a live renumber starts.
- An `editSettings` or `batch` block mirrors its writes in call order, so `setFixedPosition`
  followed by a `setConfig` that clears `fixed_position` leaves the flag cleared in `configBundle`
  and storage, as on the device.
- `RadioSessionEvent.Ready` carries the stored channels when the handshake reports none; before,
  it could carry `null` while `RadioClient.channels` held them.
- A live renumber or reconnect no longer leaves the previous session's heartbeat and presence
  loops running, so the client sends one heartbeat every 30 s.
- A `RoutingApi` call whose `requestId` another request still holds returns
  `AdminResult.NodeUnreachable` and leaves that request alone, and an RPC id the SDK allocates
  skips any a caller holds.
- `RoutingReport.relayNode` names the relay for an ACK that crossed MQTT and then arrived over LoRa.
- A stalled lockdown credential write is bounded by the fixed 30 s transport write timeout instead
  of the ACK timeout, so a long ACK wait no longer delays detecting a dead link.
- Admin setters map a `QueueStatus` rejection below 32, such as `DUTY_CYCLE_LIMIT` (9), through the
  `Routing.Error` table as RPCs already did, instead of `RateLimited`, and a duty-cycle NAK returns
  `Failed(DUTY_CYCLE_LIMIT)` instead of `NodeUnreachable`, which is left for a lost link.
- Concurrent `AdminApi.editSettings` and `batch` calls to the same node run one at a time, in call
  order, across every `AdminApi` of the client, instead of interleaving their begin, writes and
  commit into the node's single edit transaction. A call to another node does not wait. Nesting
  either call inside its own block throws `IllegalStateException` for the same node, and for
  another node whose edit another caller has open.
- On Android, `BleTransport(address)` finds the radio by an address-filtered scan in every
  `connect()` and connects from its advertisement, as the Meshtastic Android app does. A connect
  by bare MAC failed while Android's device cache was empty, as after a Bluetooth restart, until
  the autoConnect fallback took over. A bonded radio gets one 5-second scan, then its bonded
  handle; an unbonded radio gets three 5-second scans a second apart, then `connect()` fails with
  `MeshtasticException.Transport`.
- The Android BLE transport waits 3 seconds, not 1, between a GATT cache refresh and the
  reconnect that rediscovers services.
- A BLE link failure that lands after `disconnect()` no longer publishes a recoverable
  `TransportState.Error` over `Disconnected`; the abandoned `connect()` ends cancelled.
- A desktop `BleTransport(address)` whose deferred scan times out after `disconnect()` stays
  `Disconnected` and fails the abandoned `connect()` as cancelled, instead of publishing a
  recoverable `TransportState.Error`.
- `DeviceCapabilities.supportsStatusMessage` is true from firmware 2.7.20, and `supportsTakConfig`
  and `supportsTrafficManagementConfig` from 2.8.0, matching the firmware releases that added them.
- The `AdminApi.set*Config` builders no longer reset the rest of a section. Each one reads the
  section from the device it targets, applies the block to that value and writes it back, so
  `setLoraConfig { newBuilder().hop_limit(5).build() }` keeps the region and `tx_enabled`. A
  read that fails is returned and nothing is written; a read without the section returns
  `AdminResult.Failed(Routing.Error.NO_RESPONSE)`.
- `connect()` holds its admission until its run's scope, config-save channel and coroutines are
  installed. A `disconnect()` from another thread in that window no longer lets a second
  `connect()` start a run that the first then overwrote with its cancelled scope; the second
  `connect()` gets `AlreadyConnected` until the first has returned.
- `BleTransport` reports a link the radio or OS drops as a recoverable `TransportState.Error`
  when Kable reports `Disconnecting` first, as its desktop backend does. It used to publish
  `Disconnected`, which the engine ignores, so a desktop session with the BLE heartbeat off stayed
  `Connected` on a dead link.
- A BLE connect attempt without `BleResilience.autoConnectFallback` now fails after 15 seconds
  and is retried, then fails with `MeshtasticException.Transport`. On the desktop an attempt
  could hang for minutes while Kable rescanned for a radio BlueZ had dropped.
- Over BLE, an admin command to the connected node whose firmware handler takes the link down
  before answering (`commit_edit_settings`, `set_config`, `set_module_config`, the resets,
  `restore_preferences`, DFU) reports `AdminResult.Success` once the transport wrote it and the link
  dropped, instead of `NodeUnreachable`. A command still queued when the link drops does not.
- A remote admin read whose `MAX_RETRANSMIT` comes from the connected radio keeps waiting for the
  reply and reports `Failed(MAX_RETRANSMIT)` only at timeout, so a reply that arrives over another
  path is no longer lost.
- `MeshEvent.QueueStatusChanged` fires for every queue status the device sends after the
  handshake, and `MeshEvent.MqttConnected` and `MeshEvent.MqttDisconnected` fire when a local
  `getDeviceConnectionStatus()` response shows the device's MQTT broker connection changed.
- A unicast send without `want_ack` or `want_response` completes as soon as it is dispatched, as a broadcast one
  already did; its `MessageHandle` no longer stays in `Sent` until disconnect.
- `sessionEvents` delivers the events of a handshake, such as a `ClientNotification` the firmware
  queued while no client was connected or an MQTT client-proxy message, after the `Ready` snapshot
  in receive order with the held packets. They were dropped before.
- A frame that arrives between the inter-stage heartbeat and the Stage 2 request is no longer
  lost. A reconnecting radio delivers its queued phone packets there, so a text message received
  while the link was down now reaches `RadioClient.packets` and `RadioClient.sessionEvents` once
  the session is `Ready`.
- A packet with `hop_start` 0 from a sender that stamps `Data.bitfield` (firmware 2.5 and later)
  sets the node's `hops_away` to 0, as firmware `getHopsAway` does. Before, the node kept its
  previous hop count.
- On the JVM, `BleTransport(address)` accepts a Bluetooth MAC as well as a saved
  `DiscoveredRadio.address`. A MAC on Linux used to crash Kable's native layer, which parses the
  identifier as JSON there; an address that is not a valid identifier for the host is now
  resolved by a scan of up to 15 seconds in the first `connect()`, which fails with
  `MeshtasticException.Transport` when no advertisement matches.
- A connected node that renumbers on its first region set no longer leaves that write timing out.
  Firmware never acks it; its queue status and the `my_info` naming the new number complete it,
  and the session restarts on the same link with the new identity.
- The liveness watchdog fires between 60 and 65 s after the last decoded `FromRadio`. It
  checked every 30 s, so a link that went quiet just before a check timed out after about 31 s.
  During a lockdown wait it is armed from the lockdown status, so a socket that goes half-open
  before any heartbeat reply still fails the `connect()`.
- The liveness watchdog arms only after the firmware has answered a heartbeat, and only while the
  engine sends periodic heartbeats on the link. Firmware before 2.7.4 sends no reply, so a quiet
  mesh dropped a healthy session every 60 s, and so did a BLE link with `disableBleHeartbeat()`.
- A handshake waiting for a lockdown unlock now sends heartbeats and runs the liveness watchdog,
  so a half-open TCP socket fails the waiting `connect()` with `Transport("liveness timeout")`
  after 60 s of silence instead of waiting forever. Firmware answers heartbeats on a link it has
  not authorized.
- Local admin on a node with `security.is_managed` is sent once the link has reported a lockdown
  status. Firmware built with PhoneAPI access control reports one on every link and accepts local
  admin from a link it has authorized; the SDK returned `Unauthorized` without sending.
- Android AARs now ship each module's `consumer-rules.pro` as `proguard.txt`, so an app that
  shrinks with R8 keeps the Kable fields `BleResilience(refreshStaleGattCache = true)` reads by
  reflection instead of silently skipping the refresh.
- `sendDirectMessageEncrypted` fills `public_key` with the recipient's stored key, as the
  Android app does. Firmware rejects a packet whose `public_key` differs from its own copy.
- `connect()` reaches `Connected` at the Stage 2 `config_complete_id` and seeds the local session
  passkey afterwards, as the Android app does. Firmware replays every node's stored position and
  telemetry right after that completion, and while the engine held packets for the seed reply a
  large NodeDB overflowed the 64-packet handshake buffer, closing `sessionEvents` on every
  connect. A seed reply that arrives after `Connected` still latches the passkey.
- A `RadioClient` can `connect()` again after `disconnect()`, or after a failed or cancelled
  `connect()`. The second call used to throw "Channel was closed".
- `RemoteShellApi.send` queues each frame behind the paced radio queue, as `sendPaced` does. A
  typed burst sent unpaced overflowed NimBLE firmware's three-write inbound queue, which drops the
  rest.
- `requestPosition` sends the device's hop limit. Without `want_ack`, firmware leaves a zero hop
  limit in place, so the request never passed a direct neighbor.
- kotlinx-coroutines-core and -android are compiled against 1.8.1 again, after a dependency update
  raised them to 1.11.0, so hosts whose runtime pins 1.8.x (ATAK plugins) can load `:core`,
  `:storage-sqldelight` and `:transport-serial`. Renovate now holds them to 1.8 patches.
  `:transport-tcp` and `:transport-websocket` resolve 1.11.0 through Ktor 3.6.1, and
  `:transport-ble` through Kable 0.45.0, so the floor does not cover those three. Tests use
  kotlinx-coroutines-test 1.11.0, which `:testing` passes on to its consumers.
- With auto-reconnect on, a handshake that times out or drops while a sleeping device's wake window
  is open is retried as part of the sleep, counted against neither `maxAttempts` nor
  `maxHandshakeRecoveries`; the device counts as asleep until a handshake reaches `Connected`.
- `send(packet)` with a nonzero `packet.id` returns a `MessageHandle` whose `id` is that packet id,
  as `sendPaced` already did. Before, the handle carried an unrelated SDK id.
- Keep decoded USB and serial frames in order when the SDK reader's queue fills, and release the
  Android USB port if its setup is cancelled.
- A passkey buffered during the handshake no longer replaces a newer passkey for the
  same node, so `adminSessions` keeps the latest observation by receipt time.
- The first region set on firmware 2.8.0, which renumbers the node without resending `my_info`,
  now ends with the session on the new number. A region write answered only by its queue status
  completes as `Success` after five seconds and restarts the handshake, and a write refused for
  the old number with `PKI_SEND_FAIL_PUBLIC_KEY`, such as an `editSettings` commit, is resent to
  the number its NAK names.
- The commit of an `editSettings` batch that sets the first region now lands on firmware 2.8.1 and
  later. It left for the old number before `my_info` named the new one, failed with
  `PKI_SEND_FAIL_PUBLIC_KEY`, and the transaction never committed, so nothing persisted. It is now
  resent to the new number, and the session restarts after it settles. Admin requests made before
  that restart also go to the new number.
- A paced admin write whose queue status carries a `Routing.Error` above 35, such as
  `PKI_SEND_FAIL_PUBLIC_KEY`, returns `AdminResult.Failed` with that error instead of
  `AdminResult.RateLimited`, and an RPC fails with it instead of `NodeUnreachable`.
- A `rebooted` frame before the Stage 1 `my_info` ends the attempt with
  `MeshtasticException.Transport` and `MeshEvent.DeviceRebooted` when no `my_info` follows within
  2 s, instead of waiting out the 20 s Stage 1 timeout. Firmware before 2.8 sends it from the
  serial console at boot, so the request it answers went to the previous boot.
- The position and telemetry history firmware replays after the handshake no longer marks every
  node as heard now: a replayed packet without `rx_time` leaves `last_heard` alone, and no replay
  emits `CameOnline` or refreshes presence. A live packet without `rx_time` is still stamped with
  the engine clock.
- `TcpTransport` no longer fails a read after 65 s of silence. Firmware before 2.7.4 sends nothing
  back for a heartbeat, so a healthy TCP link on a quiet mesh dropped every 65 s; the engine's
  liveness watchdog, armed by a heartbeat reply, covers a dead link on newer firmware.
- Report a clean TCP peer close promptly. A transport read or write failure, including that EOF
  and a stalled write past the transport write timeout, now reconnects when auto-reconnect is enabled instead
  of ending the session.
- `TelemetryApi` requests now name the variant they want with one empty submessage, set
  `Data.dest` and go out on the node's heard channel with the device hop limit. Firmware
  answers no request whose `Telemetry` names no variant, so these RPCs used to time out.
- `RoutingApi.traceRoute` sends on `TRACEROUTE_APP` with `want_ack`, `Data.dest`, the heard
  channel and the device hop limit, and resolves on the `TRACEROUTE_APP` reply. It sent a
  `Routing.route_request` on `ROUTING_APP`, which firmware never answers.
- A remote `requestNeighborInfo` carries `want_ack`, `Data.dest`, the heard channel and the device
  hop limit; without `want_ack` firmware kept a zero hop limit.
- Ignore late transport-state updates after the engine inbox has closed during disconnect.
- `WebSocketTransport.connect()` no longer hangs when its attempt is cancelled before it starts,
  closes the session when it is cancelled just after the upgrade, cancels an earlier concurrent
  attempt atomically, and reports a message the engine rejects as too big as the same
  non-recoverable error as the SDK's own size check.
- `WebSocketTransport` publishes the session-ending error when Ktor cancels the incoming channel
  after rejecting an over-limit message, instead of leaving the state at `Connected`.
- XModem file transfer no longer sends `CAN` where firmware would delete a file it should keep
  (after a refused upload request, or after the final `EOT`), re-acknowledges a block the device
  repeats after a lost ACK, ignores a late cleanup reply from an earlier download attempt, and
  documents the full timeout bounds.

### Security

- An admin reply from another node completes a request, or refreshes that node's session passkey,
  only when it is PKI-encrypted or arrives on the legacy `admin` channel. `from` and `request_id`
  travel in the clear, so a reply under a shared channel key could otherwise be forged.
- Engine logs no longer carry the transport address or packet contents: the connect line names
  the transport kind and a 4-hex digest instead of the BLE MAC or TCP host, and the settle-buffer
  and node-flow drop warnings name only the frame variant and node number.
- `SessionPasskey.toString()` no longer prints the passkey bytes.

## [0.2.0] — 2026-10-03

The first release on Maven Central since 0.1.0. 0.1.1 was never tagged or published, so this
release also carries the 0.1.1 changes below.

### Breaking

- `MeshPacket.rx_rssi` is an `optional int32` from protobufs 2.8, so the generated Kotlin property
  is `Int?` rather than `Int`. `:core` re-exports the proto types via `api`, so code reading
  `rx_rssi` off a raw `MeshPacket` must handle `null`.

### Added

- `RadioClient.Builder.skipNodeDb()` — opt into a config-only connect that
  stops the handshake after Stage 1 and never issues the `want_config_id = 69421` NodeDB request.
  Cuts the bulk of BLE connect latency on large meshes for config editors, provisioning tools and
  CLI one-shots. Config bundle, channels, own node, admin RPCs and send/receive are unaffected;
  peers are only known once heard live. See `docs/api-reference.md` → "Config-only connect".

### Changed

- `org.meshtastic:protobufs` 2.7.26 → 2.8.1, a tagged release built with Wire's `buildersOnly`.

### Fixed

- `MeshPacket.toRadioMetrics()` no longer discards a packet carrying a genuine 0 dBm `rx_rssi`.
  The old guard treated `rx_rssi == 0` as "no metrics", which is exactly the ambiguity 2.8.0 made
  the field `optional` to remove — an SX126x can report exactly 0 dBm. The guard now tests
  presence: a real zero survives, and `null` comes back only for a packet carrying no reading at
  all, with `rx_rssi` absent and `rx_snr` zero.

### Release verification

The conformance sweep ran against the meshtastic-mcp simulated TCP radio, as for 0.1.1, with the
same result: cs1/cs3/cs5 pass, cs2 and cs6 fail on the simulator limitations recorded there,
cs4/cs7 skipped (no peer node). Transcript: `docs/release-history/v0.2.0-conformance.md`.

## [0.1.1] — 2026-08-18

Dependency-refresh release; no public API change (`checkKotlinAbi` clean, `api/*.api`
untouched since 0.1.0).

### Changed

- Kotlin 2.4.0 → 2.4.10 and SKIE 0.10.13 → 0.10.14 (adds Kotlin 2.4.10 support plus SKIE's
  Gradle configuration-cache / isolated-projects fixes).
- `org.meshtastic:protobufs` 2.7.25 → 2.7.26.
- Ktor 3.5.1 → 3.5.2, Okio 3.17.0 → 3.18.1, Kable 0.44.1 → 0.44.3.

### Release verification

The pre-release conformance sweep ran against a **simulated TCP radio** (the meshtastic-mcp
replay device at `127.0.0.1:4403`) — no bench radio was available, and the SDK currently has
no published consumers. cs1/cs3/cs5 pass; cs2 (broadcast terminal state) and cs6 (instant
reconnect) fail **identically with the released v0.1.0 CLI against the same simulator**, so
both are simulator limitations (no routing-ACK emission; single-client teardown race), not
candidate regressions. cs4/cs7 skipped (no peer node). Full gate (`check`, `checkKotlinAbi`,
`publishToMavenLocal`, tooling guardrails) green on the release commit.

## [0.1.0] — 2026-07-16

First published release (Maven Central). Everything under [0.1.0-rc1] below is included; the
sections here cover the rc cycle — changes between `v0.1.0-rc1` and `v0.1.0`.

### Breaking

Breaking relative to the unpublished `v0.1.0-rc1` tag only. Pre-1.0 policy would put breaking
changes in a MINOR bump (see `docs/versioning.md`), but nothing has ever been published to
Maven Central, so these break no external consumer and ship in 0.1.0.

- **`RadioClient.close()` / `AutoCloseable` removed** — the blocking bridge
  (`runBlocking { disconnect() }`) was an ANR/deadlock trap on Android and iOS main threads.
  Lifecycle is suspend-only; `withConnection { }` (below) is the structured replacement for
  `use { }`.
- **One byte-string vocabulary.** `Frame`, `SessionPasskey`, and the streaming `send` overload
  now use `okio.ByteString` — the type Wire-generated proto fields already force into the
  surface. `send(portnum, payload: kotlinx.io.Buffer)` is replaced by
  `send(portnum, payload: okio.ByteString)`; `kotlinx-io` is no longer a dependency of any
  published module (and is now detekt-banned via `ForbiddenImport`).
- **`sendText` parameter order is now `(text, to, channel, replyId)`** — aligned with
  `sendReaction(emoji, to, channel, replyId)`. Source-compatible for named/text-only callers;
  binary signature changed (value-class mangling).
- **Duplicate typed-decoder family removed** (`decodeAsText`, `decodeAsPosition`,
  `decodeAsUser`, `decodeAsNodeInfo`, `decodeAsTelemetry`, `decodeAsRouting`, `decodeAsAdmin`)
  — they shadowed the canonical `asText()`/`asPosition()`/… accessors in `PayloadAccessors.kt`.
  The generic `MeshPacket.decodeAs(adapter)` escape hatch remains (no portnum guard — for
  Paxcount/StoreAndForward-style payloads).
- **New sealed variants break exhaustive `when`s:** `MeshEvent.MqttProxyMessage`,
  `MeshEvent.XmodemPacket`, `MeshEvent.FileInfoReceived` (named to avoid shadowing
  `org.meshtastic.proto.FileInfo`), `MeshEvent.LockdownStatusChanged`, and
  `SendFailure.QueueRejected(res)`.
- **`nodes` flow seeding model:** every subscription now receives a fresh
  `NodeChange.Snapshot` first (seeded via `onSubscription` from the engine's current node
  map); the engine's SharedFlow no longer keeps a replay slot. Previously a late subscriber
  got whatever **delta** happened to be emitted last instead of the Snapshot, leaving
  `nodeMap()`-style folds near-empty until the next reconnect.
- **Session passkeys are no longer persisted** — they are per-node and in-memory only. The
  local node's passkey is never required (firmware rewrites phone packets to `from = 0`,
  which is passkey-exempt) and remote passkeys expire after ~4 minutes, so persistence bought
  nothing. `DeviceStorage.saveSessionPasskey`/`loadSessionPasskey` remain in the interface as
  host-facing capabilities (like `loadNodes`).

### Added

- **on-device BLE conformance harness:** `:transport-ble:connectedAndroidDeviceTest` runs the cs1–cs6 conformance envelope against a real radio from an Android device — scan by service UUID (bonded-first), production `BleTransport(address)` factory connect, two-stage handshake, read-only admin RPC round-trips, a >MTU-23 write proof, SQLDelight persistence, and same-transport reconnect. Skips (does not fail) when no Meshtastic radio is advertising, so CI never needs hardware. This harness caught every Fixed item below tagged *found on hardware*.

- **events:** Inbound MQTT client-proxy, XModem, and file-info frames are now surfaced as typed events — `MeshEvent.MqttProxyMessage`, `MeshEvent.XmodemPacket`, `MeshEvent.FileInfoReceived` — instead of being dropped with a `ProtocolWarning`. Outbound counterparts go through `RadioClient.sendRaw(ToRadio(...))`.
- **admin / lockdown:** `AdminApi.lockdown(LockdownAuth)` drives storage lockdown on hardened (`MESHTASTIC_LOCKDOWN`) firmware builds — provision, unlock, or `lock_now`. Local-only by design (a `forNode(...)`-scoped call returns `Unauthorized`; the firmware consumes the passphrase inline on the phone link and never routes it over the mesh) and fire-and-forget (the device replies with a fresh `lockdown_status` rather than a routing ACK). Completes AdminMessage coverage — every `payload_variant` is now exposed.
- **events:** `MeshEvent.LockdownStatusChanged` surfaces the device's `FromRadio.lockdown_status` report (sent right after `config_complete_id` and after each `lockdown` command) as a typed event. The device's runtime status report — not a firmware-version flag — is the source of truth for lockdown availability (lockdown is a build-time firmware option).
- **send:** `SendFailure.QueueRejected(res)` — a firmware `QueueStatus` enqueue rejection now fast-fails the `MessageHandle` (and any pending admin RPC sharing the wire id) instead of waiting out the full ACK timeout.
- **send:** `RadioClient.sendText(..., replyId)` for threaded replies (`decoded.reply_id` without the emoji flag).
- **engine:** Mesh packets received while the handshake is still in flight (live traffic interleaved with the config/NodeDB drain, including the seeding window) are now buffered (drop-oldest at 64, observable via `PacketsDropped`) and flushed through the normal packet pipeline at Ready — previously they were silently dropped.
- **ergonomics:** `RadioClient { … }` builder-lambda factory (sugar over `RadioClient.Builder`; Swift callers keep the builder).
- **ergonomics:** `RadioClient.withConnection(teardownTimeout = 10.seconds) { … }` — connect, run the block, and always disconnect (success, exception, and cancellation; teardown runs under `NonCancellable`, bounded by `teardownTimeout` so a wedged transport cannot pin the caller forever).
- **ergonomics:** `Flow<NodeChange>.asNodeMap()` / `RadioClient.nodeMap()` — fold the node delta stream into a live `Map<NodeId, NodeInfo>` (the accumulator every consumer otherwise hand-writes), ready for `stateIn`.
- **testing:** `FromRadio.toFrame()` — encode a device-side envelope into a wire `Frame` for use with `FakeRadioTransport.injectFrame` (replaces eight per-test-file copies of the framing helper).

### Fixed

- **transport-ble:** `toradio` writes now use **acknowledged writes** (`WriteType.WithResponse`). Firmware declares the characteristic `CHR_PROPS_WRITE` only — write-without-response is not in its properties, and Android refuses the write outright, making every connect fail on real radios. *Found on hardware.*
- **engine:** wire packet ids are **randomly seeded per engine instance**. The counter previously started at 1 every session, so a reconnect (or app restart) re-issued the same ids and the firmware's ~10-minute packet-history dedup silently dropped the repeats — every admin RPC in the new session timed out with no response. Matches the reference clients' randomized id seeding. *Found on hardware.*
- **engine:** the Stage-2 commit now publishes `configBundle` and `channels` to the public flows **synchronously on the actor** before `connect()` resumes. Publication previously ran inside the async storage flush, so on devices with real storage latency `connect()` returned while `configBundle.value` was still `null`. *Found on hardware.*
- **storage-sqldelight (Android):** WAL is enabled via `SupportSQLiteDatabase.enableWriteAheadLogging()` instead of `execSQL("PRAGMA journal_mode=WAL")` — the PRAGMA returns a result row, which Android's `execSQL` rejects (`SQLiteException: Queries can be performed using SQLiteDatabase query or rawQuery methods only`), failing the second storage activation. *Found on hardware.*
- **transport-ble:** `frames()` is re-collectable after `disconnect()`, honouring the documented reuse-after-disconnect contract — the frame channel is recreated per connect cycle and the single-collector guard resets when a collector completes. Previously the second session's engine failed with "frames() may only be collected once per instance". *Found on hardware.*

- **remote admin / firmware conformance:** `QueueStatus.res` is decoded in the firmware's **ERRNO namespace**, not `Routing.Error`: `35` (`ERRNO_SHOULD_RELEASE`) is success and now counts as `Sent`; ERRNO rejections (`32` queue-full, `33` no interface, `34` radio disabled) fail pending admin RPCs as `NodeUnreachable`; values `1..31` (genuine `Routing.Error` codes such as `DUTY_CYCLE_LIMIT`) map through the normal routing-error taxonomy. Previously `res = 32` was misread as `BAD_REQUEST`, `33` as `NOT_AUTHORIZED`, and the success code `35` produced a false failure.
- **remote admin:** session passkeys are only latched from **response-shaped** admin messages. Previously a remote node administering *us* would have its request — carrying the passkey *we* issued — latched under the remote's key, poisoning our next RPC to it.
- **remote admin:** the fire-and-forget admin path (`enterDfuMode`, `setTimeOnly`) now posts through the engine actor's inbox; it previously prepared the packet on the caller's coroutine, structurally mutating the actor-owned per-node passkey map (data race under ADR-002).
- **remote admin:** the managed-mode client-side gate now applies only to **local** targets. Firmware rejects only local admin on a managed device (`from == 0` branch); remote targets authorize against their own admin keys — managed-fleet deployments administer remote managed nodes from a managed local node.
- **engine:** a `want_config_id` retry restarts the firmware's config drain from scratch, which previously duplicated channels / config sections in the committed `ConfigBundle` and `channels` state. Stage 1 accumulators now replace by key, latest occurrence wins.
- **engine:** `FromRadio.lockdown_status` was silently dropped, then briefly surfaced as a generic `ProtocolWarning`; it now lands as the typed `MeshEvent.LockdownStatusChanged` (see Added), handled identically mid-handshake and post-handshake.
- **engine:** the Stage-1 settle replay no longer drops buffered frames that follow a duplicate Stage-1 completion — the unprocessed remainder is re-buffered for the next settle window.
- **engine:** the seeding window no longer silently drops non-packet `FromRadio` variants — `node_info` merges into the node DB and the rest route through the shared auxiliary handler (client notifications, MQTT proxy, …).
- **engine:** caller-supplied wire-id collisions are now rejected with `SendFailure.IdCollision` at the wire-id key in `dispatchSend`; previously the second send silently overwrote the first handle's bookkeeping, stranding it un-completable forever.
- **engine:** the Stage-2 commit's async storage flush now snapshots the accumulated nodes/channels/heartbeats before launching; it previously iterated live actor-owned collections off-actor (CME risk mis-reported as `StorageDegraded`).
- **transport-ble (Android):** the delayed connection-priority downgrade is now scheduled on a per-connect-cycle scope cancelled at `disconnect()` — a stale 30-second timer from session N can no longer fire mid-handshake of session N+1 (the auto-reconnect path) and defeat the priority boost.

### Changed

- **remote admin (breaking behavior, correctness):** Remote admin packets are now routed the way modern firmware (2.5+) requires — `pki_encrypted = true` + the target's `public_key` on channel 0 when both nodes have published keys, falling back to a channel named `admin` otherwise, with priority `RELIABLE`. Previously remote admin went out on channel 0 in the clear and was rejected by current firmware.
- **remote admin:** Session passkeys are now cached **per node** (each node issues its own in its admin responses; every inbound admin response refreshes the issuer's entry). Previously a single shared slot cross-contaminated concurrent admin sessions against different nodes and stamped the *local* node's passkey onto remote targets. The `SessionKeyExpired` single-shot retry now re-seeds against the *target* node.
- **storage:** Documented that `DeviceStorage.loadNodes()` is never called by the engine (node DB reseeds from the handshake); it exists for hosts (offline node access) and tests.
- **transport-ble (Android):** `BleTransport(address)` now negotiates the ATT MTU (517) after each link establishment and requests `CONNECTION_PRIORITY_HIGH` for the 30-second handshake window before downgrading to Balanced. Without the MTU request Android stays at the BLE minimum (23) and any ToRadio write over 20 bytes fails.
- **docs:** `docs/api-reference.md` gains an **API conventions** section (proto exposure, byte vocabulary, no blocking bridges, data-class policy, Kotlin/Swift-first interop); SPEC bumped to v2.3 and synced; CONTRIBUTING/AGENTS house rules flipped to the okio vocabulary with superseded-by notes preserved in ADR-003.
- **build:** `org.meshtastic:protobufs` stays pinned at `2.7.25`. A field-level diff against the published `develop-SNAPSHOT` found **zero** changes across all 46 SDK-consumed proto messages — the only structural delta is the device-only `NodeDatabase` flash-storage restructure (new `NodeStatus/Position/Telemetry/Environment` entry types), which the SDK does not consume. The newer lockdown fields (`LockdownAuth.disable`, `LockdownAuth.max_session_seconds`, `LockdownStatus.State.DISABLED`) arrive in a follow-up once a stable proto release ships them.
- **build:** Kable 0.42.0 → 0.43.0 (aligns with Meshtastic-Android).
- **build:** Aligned the toolchain with Meshtastic-Android — Kotlin 2.3.21 (SKIE 0.10.12), Wire 6.4.0, Ktor 3.5.0, and stable coroutines 1.11.0 (was 1.11.0-rc02). Validated locally: iOS framework link, jvmTest, and Kotlin ABI check all green.
- **docs(SPEC.md):** Bumped spec from v2.1 to v2.2 — full post-audit sync aligning spec with shipped implementation. Key areas synchronized: AdminApi expansion (~15 → ~45 methods), `StoreForwardApi`, presence tracking (`WentOffline`/`CameOnline`), `AutoReconnectConfig`, `CongestionWarning`/`ExternalConfigChange`/`StorageDegraded` MeshEvent variants, send DSL, `connectAndAwaitReady()`, `SessionPasskey`, `ConfigBundle.deviceUIConfig`, `SendFailure.IdCollision`/`AckTimeout`/`HandshakeFailed`, `AdminResult` extensions, `ConnectionState` extensions, `MeshtasticException` context fields, convention plugin + version catalog correction (JVM 17→21, Android SDK→36, Kotlin 2.3.20).
- **docs:** Synchronized `api-reference.md`, `error-taxonomy.md`, `roadmap.md`, `module-graph.md`, `README.md`, `CONTRIBUTING.md` with spec v2.2 changes.

## [0.1.0-rc1] — 2026-05-01

Initial release candidate of the Meshtastic Kotlin Multiplatform SDK (internal team-share;
never published to Maven Central).

### Highlights

- **Full PhoneAPI handshake** — two-stage config exchange, session passkey seeding, auto time-sync.
- **Three transports** — BLE (Kable), TCP (Ktor sockets), USB-serial (jSerialComm / usb-serial-for-android).
- **Persistent storage** — SQLDelight-backed NodeDB, channels, configs with WAL + transactional writes.
- **Engine actor model** — single-writer concurrency (ADR-002), structured coroutine topology.
- **ACK correlation & retries** — `MessageHandle` tracks `Queued → Sent → Acked/Delivered/Failed` with configurable timeout.
- **Phase 2 RPC surfaces** — `AdminApi` (16 methods + `editSettings` transaction), `TelemetryApi`, `RoutingApi`.
- **Ergonomics helpers** — `NodeId.toHex()`, `ChannelUrl` codec, `BatteryStatus`, `RadioMetrics`, typed payload accessors, `connectAndAwaitReady()`.
- **Auto-reconnect supervisor** (opt-in) — exponential backoff with jitter, `ConnectionState.Reconnecting`.
- **Kotlin ABI baselines** — `checkKotlinAbi` gate on every PR.
- **Architecture enforcement** — detekt `ForbiddenImport` + `:core:verifyModuleBoundary`.
- **CI** — SHA-pinned actions, JVM matrix, iOS sim tests, CodeQL, Scorecard, dependency review.
- **CLI sample** — Mosaic TUI dashboard exercising all three transports.

### Targets

`jvm`, `androidTarget` (minSdk 26), `iosArm64`, `iosX64`, `iosSimulatorArm64`.
