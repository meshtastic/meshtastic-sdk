# Public API reference

A browsable view of the public API of `:core` and the transport, storage, and testing modules. The CI-gated ABI dumps (`<module>/api/jvm/*.api`, `<module>/api/*.klib.api`) are the authoritative list of symbols; KDoc carries the full contracts.

Payload types named without a package (`MeshPacket`, `NodeInfo`, `Config`, `User`, `Channel`, `AdminMessage`, `Routing.Error`, `FromRadio`, `DeviceProfile`, and so on) are Wire-generated types from `org.meshtastic.proto`, per [ADR-001](./decisions/001-public-api-uses-generated-protobufs.md). The SDK doesn't redeclare them. They're generated builders-only: build or change one with `Builder()` or `newBuilder()`, since they have no public constructor and no `copy()`.

## Package map

| Package | Targets | Contents |
|---|---|---|
| `org.meshtastic.sdk` | JVM, Android, iOS, JS, Wasm | `RadioClient` and its `Builder`; the sub-APIs (`AdminApi`, `TelemetryApi`, `RoutingApi`, `StoreForwardApi`, `FileTransferApi`, `RemoteShellApi`, `MqttProxyApi`); send, event, and result types; ID value classes; the `RadioTransport` and `StorageProvider` contracts; `TransportSpec`, `TransportIdentity`, `TransportTarget`; `InMemoryStorageProvider`; payload and provenance accessors; LoRa region math; `WireCodec` and `WireFraming` |
| `org.meshtastic.sdk.transport.tcp` | JVM, Android, iOS | `TcpTransport`; mDNS discovery: `TcpDiscovery`, `TcpDiscoveryEvent`, `DiscoveredTcpRadio`, `JvmTcpDiscovery`, `AndroidTcpDiscovery`, `IosTcpDiscovery` |
| `org.meshtastic.sdk.transport.ble` | JVM, Android, iOS | `BleTransport` (with `deviceLogs()`), `BleScanner`, `DiscoveredRadio`, `BleConstants`, `BleResilience`, `bleAdvertisementMatches`; on Android, `BondedBleDevice` and `bondedBleDevices(context)` |
| `org.meshtastic.sdk.transport.serial` | JVM, Android | `JvmSerialPorts`, `AndroidSerialPorts` (jSerialComm), `AndroidUsbSerialPorts` (usb-serial-for-android), `SerialPortInfo`, `SerialPortTransport`, `SerialFrameAssembler`, `MESHTASTIC_USB_VENDOR_IDS`, `isLikelyMeshtastic()` |
| `org.meshtastic.sdk.transport.websocket` | JVM, Android, iOS, JS, Wasm | `WebSocketTransport` |
| `org.meshtastic.sdk.storage.sqldelight` | JVM, Android, iOS | `SqlDelightStorageProvider`; `AndroidContextHolder` on Android |
| `org.meshtastic.sdk.testing` | JVM, Android, iOS, JS, Wasm | `FakeRadioTransport`, `TestClock`, `FromRadio.toFrame()`; `InMemoryStorage` and `InMemoryStorageProvider` are deprecated aliases of the `:core` classes |
| `org.meshtastic.sdk.internal` | | Engine, state machine, codec. Kotlin `internal`, not API |

The web targets are covered in [ADR-017](./decisions/017-web-targets.md).

## API conventions

- **Proto types are exposed unwrapped** ([ADR-001](./decisions/001-public-api-uses-generated-protobufs.md)). The SDK's ABI tracks the `org.meshtastic:protobufs` artifact, and proto bumps are priced into the SemVer table in [`versioning.md`](./versioning.md).
- **One byte-string type: `okio.ByteString`.** Wire uses it for proto fields, so SDK types (`Frame`, `SessionPasskey`, the `send(portnum, payload)` overload) use it too. `kotlinx-io` isn't a dependency.
- **No blocking bridges.** Lifecycle is suspend-only (`connect()`, `disconnect()`). `RadioClient` has no `close()`, because blocking teardown risks an ANR on the Android main thread and a deadlock on the iOS main thread. `withConnection { … }` is the scoped replacement for `use { }`.
- **Data classes are a deliberate trade.** Event and result types are `data` for structural equality in tests and exhaustive `when`. Treat `copy()` and destructuring of SDK types as conveniences, not contract: they migrate to `@Poko` classes once Poko supports the current Kotlin version ([`roadmap.md`](./roadmap.md)).
- **Kotlin and Swift first.** Java callers aren't a supported audience. Swift interop goes through SKIE and `@Throws`.

### Coordinates and the BOM

Every artifact is published under the `org.meshtastic` group as `sdk-<module>`, for example `org.meshtastic:sdk-core`. Use [`org.meshtastic:sdk-bom`](../bom/README.md) to align versions; [`versioning.md`](./versioning.md#bom) has the policy.

## `RadioClient`

The single entry point. One `RadioClient` talks to one radio ([ADR-005](./decisions/005-api-shape.md)).

### Construction

```kotlin
RadioClient.Builder()
    .transport(TcpTransport(host = "meshtastic.local", port = 4403))   // required
    .storage(SqlDelightStorageProvider(baseDir = "/var/data"))         // required, no default
    .logger(MyLogSink)                                                 // default: LogSink.Silent
    .clock(Clock.System)                                               // default: kotlin.time.Clock.System
    .coroutineContext(SupervisorJob() + Dispatchers.Default)           // default
    .autoSyncTimeOnConnect(true)                                       // default: true
    .disableBleHeartbeat()                                             // default: BLE heartbeats on
    .protocolLogging(LogLevel.DEBUG, PayloadRedactor.Default)          // default: LogLevel.NONE
    .sendTimeout(30.seconds)                                           // default: 30 s, ACK wait per unicast want_ack send
    .rpcTimeout(30.seconds)                                            // default: 30 s, per admin/telemetry/routing call
    .presenceTimeout(2.hours)                                          // default: 2 h, node offline threshold
    .autoReconnect(AutoReconnectConfig())                              // default: AutoReconnectConfig.Disabled
    .skipNodeDb()                                                      // default: off
    .build()
```

`build()` throws `IllegalArgumentException` without `transport(...)` and `storage(...)`. The builder takes a constructed `RadioTransport`; there is no `transport(TransportSpec)` overload. To turn a user-typed target into a transport, parse it with `TransportTarget.parse` (see [Transport targets](#transport-targets)) and open the matching transport in host code.

`autoSyncTimeOnConnect(true)` sends one `set_time_only` as soon as the Stage 1 `my_info` arrives, so firmware can re-date packets it queued without a clock while they are still queued (best effort; see `architecture/handshake-fsm.md`). `disableBleHeartbeat()` stops the 30 s heartbeat on BLE only; TCP and serial always send it.

Kotlin callers can use the builder-lambda factory instead:

```kotlin
val client = RadioClient {
    transport(TcpTransport(host = "meshtastic.local", port = 4403))
    storage(SqlDelightStorageProvider(baseDir = "/var/data"))
    logger(MyLogSink)
}
```

#### Config-only connect with `skipNodeDb()`

The handshake normally runs in two stages ([protocol.md §6](./protocol.md#6-handshake-state-machine)). Stage 1 (`want_config_id = 69420`) streams `my_info`, `metadata`, device and module config, channels, and the local `NodeInfo`. Stage 2 (`69421`) streams the whole node database, which dominates connect time on a large mesh over BLE.

`skipNodeDb()` stops after Stage 1: `69421` is never sent, and the session seeds the admin session passkey and goes to `Connected`. `ConfigPhase.Stage2` never appears on `connection`.

| Still works | Degraded |
|---|---|
| `configBundle`, `channels`, `ownNode` | `nodes` starts with a `Snapshot` holding only the local node |
| `admin`, `telemetry`, `routing`, `storeForward`, `files` | `nodeSnapshot()` likewise starts with only the local node |
| `send` and `sendText`, `packets`, `events`, `fromRadio` | presence events cover only nodes heard this session |

Peers appear as they're heard: any decoded packet creates the sender and refreshes its `last_heard`, `snr`, `hops_away`, and `via_mqtt`; `NODEINFO_APP` merges `user` and `POSITION_APP` merges `position` ([protocol.md, "Node DB after the handshake"](./protocol.md#node-db-after-the-handshake)). Node rows persisted by earlier sessions stay on disk but aren't loaded.

Use it for config editors, provisioning tools, and CLI one-shots. Leave it off for chat and map UIs.

#### `AutoReconnectConfig`

```kotlin
public data class AutoReconnectConfig(
    val enabled: Boolean = true,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 60.seconds,
    val maxAttempts: Int? = null,
    val backoffMultiplier: Double = 2.0,
    val jitter: Double = 0.2,
    val recoveryStep: ReconnectRecoveryStep? = null,
) {
    public companion object { public val Disabled: AutoReconnectConfig }
}

public fun interface ReconnectRecoveryStep {
    public suspend fun recover(attempt: Int, cause: MeshtasticException)
}
```

Auto-reconnect is off unless the builder receives a config with `enabled = true`; the builder's default is `AutoReconnectConfig.Disabled`. When on, the engine reconnects after a transport drop and reports `ConnectionState.Reconnecting(cause, attempt)`. The 1.0 release turns it on by default; pass `AutoReconnectConfig.Disabled` to keep it off.

`recoveryStep` runs after each backoff delay and before `transport.connect()`, for work such as requesting a USB permission again, power-cycling a radio, or waiting for the Bluetooth adapter. Throwing counts as a failed attempt. It prepares the transport the client was built with and can't swap in another.

### Lifecycle and state

| Member | Notes |
|---|---|
| `suspend fun connect()` | Suspends until `Connected`. Throws `MeshtasticException` (`AlreadyConnected`, `Transport`, `Protocol`, `StorageUnavailable`, `HandshakeTimeout`) or `CancellationException`. Not idempotent: calling it while connected throws `AlreadyConnected`, because a silent no-op hides reconnect-loop bugs. |
| `suspend fun RadioClient.connectAndAwaitReady(timeout: Duration = 30.seconds): ConfigBundle` | Calls `connect()` and returns the committed `ConfigBundle`. Throws `HandshakeTimeout(stage = "connectAndAwaitReady")` when the bundle doesn't arrive within `timeout`. |
| `suspend fun RadioClient.awaitInitialNodeDb(): Map<NodeId, NodeInfo>` | Waits for the first node map after the handshake commits. |
| `suspend fun disconnect()` | Idempotent, never throws. Fails every open `MessageHandle` with `Disconnected`, then closes the transport and storage. |
| `suspend fun <T> RadioClient.withConnection(teardownTimeout: Duration = 10.seconds, block: suspend RadioClient.() -> T): T` | Connects, runs `block`, and always disconnects, on success, exception, or cancellation. Teardown runs under `NonCancellable`, bounded by `teardownTimeout`. |
| `connection: StateFlow<ConnectionState>` | `Disconnected → Connecting → Configuring → Connected`; after a drop with auto-reconnect on, `Reconnecting → Connecting → …`. |
| `ownNode: StateFlow<NodeInfo?>` | `null` until `Connected`, then the local node. |
| `configBundle: StateFlow<ConfigBundle?>` | `null` until `Connected`; cleared on disconnect. `MyNodeInfo`, `DeviceMetadata`, configs, module configs, and `DeviceUIConfig`. Updated by unsolicited config pushes and successful local admin writes. |
| `channels: StateFlow<List<Channel>?>` | `null` until `Connected`. Updated when `setChannel` succeeds; `admin.listChannels()` re-reads all eight slots. |
| `adminSessions: StateFlow<Map<NodeId, AdminSessionStatus>>` | Admin session state per node, keyed by the node that issued the passkey. See [Admin sessions](#admin-sessions). |
| `fun adminSessionStatus(node: NodeId): Flow<AdminSessionStatus>` | `adminSessions` for one node, `NoSession` when absent; emits the current value, then changes. |

Editing another node through `admin.forNode(dest)` doesn't touch `channels` or `configBundle`, in memory or in storage, including writes made through `editSettings` and `batch`.

#### Admin sessions

```kotlin
public sealed interface AdminSessionStatus {
    public data object NoSession : AdminSessionStatus
    public data class Active(val refreshedAt: Instant, val staleAt: Instant) : AdminSessionStatus
    public data class Stale(val refreshedAt: Instant, val expiresAt: Instant) : AdminSessionStatus
}
```

A node that sends a session passkey has at least 150 s of validity left on it. A key received at time `T` is `Active` until `T + 150 s` (expected to be accepted, not guaranteed: delivery delay is unmeasured), `Stale` until `T + 300 s` (may be accepted), and absent after that (`NoSession`, certainly rejected). Any admin response carrying a passkey makes the session `Active` again. A node's entry is also removed on `ADMIN_BAD_SESSION_KEY`, disconnect, and identity rebind. Expiry is evaluated on the engine's 30 s heartbeat tick, so a transition can surface up to 30 s late. The passkey bytes are never exposed.

### Streams

| Member | Contents | Buffer | When a collector falls behind |
|---|---|---|---|
| `nodes: Flow<NodeChange>` | Node snapshot, then deltas | 256 | The new delta is discarded and `events` gets `PacketsDropped(DroppedFlow.Nodes, 1)` |
| `RadioClient.nodeMap(): Flow<Map<NodeId, NodeInfo>>` | `nodes` folded into a map; first emission is an empty map | follows `nodes` | follows `nodes` |
| `packets: Flow<MeshPacket>` | Decoded inbound mesh packets | 128 | The new packet is discarded and `events` gets `PacketsDropped(DroppedFlow.Packets, 1)` |
| `events: Flow<MeshEvent>` | Advisory events | 64 | The oldest event is dropped, unreported |
| `fromRadio: Flow<FromRadio>` | Every decoded inbound envelope, unredacted: handshake frames, config, channels, `log_record`, and packets | 256 | The oldest envelope is dropped, unreported |
| `sessionEvents: Flow<RadioSessionEvent>?` | Optional single-consumer queue | Bounded by `Builder.sessionReceive(capacity)` |
| `deviceLogs: Flow<LogRecord>` | `FromRadio.log_record`, handshake included | 64 | The oldest record is dropped, unreported |
| `displayFrames: Flow<DisplayMirrorFrame>` | Reassembled display mirror frames | 2 | The oldest frame is dropped, unreported |

The engine never waits for a collector. All five are hot: collectors share one upstream and see only what is emitted while they collect. `nodes` is the exception for the baseline: each subscription first receives a `NodeChange.Snapshot` of the current node map, so a late subscriber never misses it. The handshake also emits a live `Snapshot` when it commits, after every reconnect, which existing subscribers treat as a full replacement. `Flow<NodeChange>.asNodeMap()` is the fold behind `nodeMap()`.

`fromRadio` is for recorders and protocol analyzers. Envelopes can carry channel pre-shared keys (PSKs) and node positions, and the SDK doesn't redact or persist them. `deviceLogs` carries `FromRadio.log_record` (device logs over the phone API) on its own, so a log stream cannot push rare events out of `events`; firmware sends them on serial and TCP while `security.debug_log_api_enabled` is set, and the SDK never sets it. See [observability.md](./observability.md#device-logs).

#### Display mirror

`AdminApi.setDisplayMirror(true)` starts a continuous stream and `AdminApi.requestDisplayFrame()` asks for one frame; both are local-device only. The engine reassembles the firmware's chunks and emits each complete frame on `displayFrames` as a `DisplayMirrorFrame` (`width`, `height`, `format`, `frameId`, `data`, the partial-update rectangle `rectX`/`rectY`/`rectWidth`/`rectHeight`, and `paletteSignature`). A collector that falls behind loses the oldest undelivered frame, so a slow renderer skips ahead without stalling the engine. `displayPalette: StateFlow<DisplayPalette?>` holds the latest complete palette that `DisplayMirrorFrame.paletteSignature` refers to; it is `null` until one arrives and after a disconnect. The SDK delivers bytes only: it does not decode, composite or render.

### Session receive

Enable `RadioClient.Builder.sessionReceive()` for an ordered consumer such as an app writing to its own database. Start collecting `client.sessionEvents` before calling `connect()`. A protected radio can first emit `Lockdown` with a status requiring provisioning or authentication. Use `client.tryLockdown(auth)` on that session. The radio reports the result in a subsequent status. Each successful handshake emits `Ready` with the session ID and a snapshot of local identity, config, reported channels and nodes. Retained handshake packets follow in receive order, then live packets. Sequences start at one per session. `Ended` marks the end of that session; one that stopped at `Lockdown` and never reached `Ready` ends without it. An automatic reconnect creates a new ID on the same stream.

After `Ready` the stream also carries what the SDK derives, in the same sequence as packets: `NodeChanged` for each node-map change, `ConfigChanged` and `ChannelsChanged` with the whole new bundle or channel list, and `Event` for each `MeshEvent` except `LockdownStatusChanged`, `QueueStatusChanged` and `PacketsDropped`. A packet comes before the changes it causes. An acknowledged local admin write appears once the call returns: config and channel writes as `ConfigChanged` or `ChannelsChanged`, and `setOwner`, `setFavorite`, `setIgnored` and `removeNode` as `NodeChanged`. A consumer that projects these into its own store needs no other flow.

Call `requestNodeDbRefresh()` to start a nodes-only download on this connection. The matching completion produces `NodeDbRefreshed` in receive order. Packets continue through the engine during the download. Overlapping requests queue a single follow-up. If the download stalls, the SDK ends the connection to prevent a late response from being attributed to another request.

The engine never waits on this queue. When it fills, the collector receives the accepted prefix and then `RadioSessionOverflowException`. The error identifies the affected session and, for a rejected sequenced event, its sequence. A handshake buffer overflow ends the stream before a snapshot can imply complete coverage. To resume ingestion after overflow or collector cancellation, start a new client and reconcile any missing history through a source that retained it. Sequence numbers cover the SDK handoff. Loss in firmware or transport buffers can happen before that boundary.


Use `sendPaced(packet)` for durable sends that need distinct proof of SDK admission, dispatch and radio queue acceptance. It preserves a supplied wire ID. The next paced packet waits for a matching queue receipt, routing result or five seconds after the transport write. ID-zero queue reports also come from heartbeats and cannot identify a packet. The queue receipt remains unconfirmed when it is lost. Routing results can still settle the message after the queue slot opens.

### Outbound

| Member | Returns | Throws |
|---|---|---|
| `send(packet: MeshPacket)` | `MessageHandle` in `Queued` | `PayloadTooLarge`, `NotConnected` |
| `sendText(text: String, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), replyId: Int = 0)` | `MessageHandle` | same as `send` |
| `trySendRaw(frame: ToRadio)` | Whether the outbound queue accepted the encoded frame | — |
| `sendReaction(emoji: String, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), replyId: Int)` | `MessageHandle` | same as `send` |
| `suspend send(portnum: PortNum, payload: ByteArray, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), wantAck: Boolean = false, hopLimit: Int? = null)` | `MessageHandle` | same as `send` |
| `suspend send(portnum: PortNum, payload: ByteString, …)` | `MessageHandle` | same as `send` |
| `requestNodeInfo(node: NodeId)` | `MessageHandle`; the reply arrives on `nodes` | `NotConnected` |
| `requestNodeInfo(node: NodeId, channel: ChannelIndex, user: User? = ownNode.value?.user)` | `MessageHandle`; sends `user` as the payload, no `want_ack` | `NotConnected`, `IllegalArgumentException` for our own node |
| `sendRaw(frame: ToRadio)` | `Unit`; not tracked or acknowledged | `NotConnected` |
| `suspend nodeSnapshot()` | `Map<NodeId, NodeInfo>` | `NotConnected` |

`PayloadTooLarge` fires when `decoded.payload` exceeds `DATA_PAYLOAD_LEN` (233 bytes). `sendText` and `sendReaction` set `want_ack`. Extensions in the same package cover common payloads: `sendPosition(latLng, to, channel, wantAck)`, `requestPosition(from, channel)`, `sendDirectMessage(to, text, channel, wantAck)`, and `sendDirectMessageEncrypted(to, text, wantAck)`, which sets `pki_encrypted`.

### Channel-aware helpers

| Member | Notes |
|---|---|
| `RadioClient.channelFor(node: NodeId): ChannelIndex` | `NodeInfo.channel` for a node heard live; `0` for an unknown node, our own node and the primary channel |
| `RadioClient.sendDirectMessageOnHeardChannel(to, text, wantAck)` | `sendDirectMessage` on `channelFor(to)` |
| `RadioClient.requestPositionOnHeardChannel(from)` | `requestPosition` on `channelFor(from)` |
| `RadioClient.requestPositionOnHeardChannel(from, position: Position?)` | `requestPosition(from, channel, position)` on `channelFor(from)` |
| `RadioClient.requestNodeInfoOnHeardChannel(node)` | `requestNodeInfo(node, channel)` with our own user on `channelFor(node)` |
| `RadioClient.requestPaxcount(node)` | Empty `Paxcount` on `PAXCOUNTER_APP` with `want_response` and `Data.dest`; decode the reply with `asPaxcount()` |

`sendDirectMessage` and `requestPosition` keep their `ChannelIndex(0)` default. `requestPosition` sends an empty `POSITION_APP` payload with `want_response` at `BACKGROUND` priority. `requestPosition(from, channel, position)` sends a non-null `position` instead, with `time` set from the client clock when it is `0`, and the device hop limit. Set coordinates only when they are valid; `null` keeps the empty payload.

### Waypoint helpers

| Member | Notes |
|---|---|
| `Waypoint.isExpired(now: Instant): Boolean` | `expire == 0` never expires, `expire == 1` is deleted, otherwise expired once `now` reaches it |
| `Waypoint.canBeEditedBy(node: NodeId): Boolean` | `locked_to == 0` or equal to `node` |
| `newWaypointId(random: Random = Random): Int` | non-zero |
| `RadioClient.sendWaypoint(waypoint, to, channel, wantAck): MessageHandle` | `WAYPOINT_APP`; throws `IllegalArgumentException` for id `0` and `MeshtasticException.Protocol` for a waypoint locked to another node |
| `RadioClient.deleteWaypoint(waypoint, to, channel, wantAck): MessageHandle` | sends the waypoint with `expire = 1` |

The SDK keeps no waypoint store; message and waypoint history belong to the consumer (roadmap R-10).

### Send DSL

```kotlin
val handle = client.send {
    text("hello mesh")
    to(NodeId.BROADCAST)
    channel(ChannelIndex(0))
    wantAck(true)
}
```

`sendPaced { … }` takes the same block and returns a `PacedMessageHandle`, as `sendPaced(packet)` does.

`send { … }` configures a `SendBuilder`: `text(String)`, `data(PortNum, ByteArray)`, `position(LatLng)`, `proto(MeshPacket)`, `to(NodeId)`, `channel(ChannelIndex)`, `wantAck()`, `wantAck(Boolean)`, `hopLimit(Int?)`.

A `hopLimit` left unset, like `hopLimit = null` on the typed `send(portnum, …)` overloads, sends the device's `lora.hop_limit` from `configBundle`, or the firmware default of 3 before the bundle lands. The SDK resolves it on the host because firmware substitutes its default for a `0` only when `want_ack` is set. `send(packet: MeshPacket)` and `proto(...)` go out as given.

### Sub-APIs

`client.admin`, `client.telemetry`, `client.routing`, `client.storeForward`, `client.files`, `client.remoteShell`, and `client.mqttProxy` are created on first access and work while the client is `Connected`. Each RPC waits up to `Builder.rpcTimeout` and reports failure as a result value, not an exception.

## `MessageHandle`

```kotlin
public class MessageHandle internal constructor(…) {
    public val id: MessageId
    public val state: StateFlow<SendState>
    public suspend fun await(): SendOutcome
    public fun cancel()
}

public suspend fun MessageHandle.retry(): MessageHandle
public suspend fun MessageHandle.retryWith(policy: RetryPolicy): SendOutcome   // RetryPolicy.None, Fixed, ExponentialBackoff
```

Invariants ([ADR-005](./decisions/005-api-shape.md)):

1. Disconnect fails every open handle with `Disconnected` before transport teardown completes, so `await()` always returns.
2. Cancelling the coroutine inside `await()` rethrows `CancellationException`; the handle's `state` keeps updating for other observers. `cancel()` withdraws the send.
3. `cancel()` is idempotent. Before `Sent` it removes the packet from the outbound queue and sets `Failed(Cancelled)`; after `Sent` it has no effect.
4. `PayloadTooLarge` never reaches a handle. `send()` throws it and returns nothing.

## `SendState`, `SendFailure`, `SendOutcome`

```kotlin
public sealed interface SendState {
    public data object Queued : SendState
    public data object Sent : SendState           // device QueueStatus accepted mesh_packet_id
    public data object Acked : SendState          // unicast routing ACK heard
    public data object Delivered : SendState      // broadcast: a relay was heard
    public data class Failed(val reason: SendFailure) : SendState
}

public sealed interface SendFailure {
    public data object NoRoute : SendFailure                    // Routing.Error NO_ROUTE or GOT_NAK
    public data object MaxRetransmit : SendFailure              // MAX_RETRANSMIT
    public data object Timeout : SendFailure                    // Routing.Error TIMEOUT
    public data object DutyCycleLimit : SendFailure             // DUTY_CYCLE_LIMIT
    public data object RateLimited : SendFailure                // RATE_LIMIT_EXCEEDED
    public data object Disconnected : SendFailure               // transport dropped before the send completed
    public data object HandshakeFailed : SendFailure            // queued before Connected, handshake failed
    public data object Cancelled : SendFailure                  // MessageHandle.cancel() before Sent
    public data object IdCollision : SendFailure                // packet id matches an in-flight send
    public data object AckTimeout : SendFailure                 // no ACK within Builder.sendTimeout
    public data class QueueRejected(val res: Int) : SendFailure // device transmit queue rejected it (QueueStatus.res)
    public data class Other(val routingError: Routing.Error) : SendFailure
    public data class Unknown(val message: String) : SendFailure
}

public sealed interface SendOutcome {
    public data object Success : SendOutcome
    public data class Failure(val reason: SendFailure) : SendOutcome
}
```

`await()` maps `Acked` and `Delivered` to `Success` and `Failed(reason)` to `Failure(reason)`. `SendOutcome` is its own type, not an alias of `SendState`. The wire mapping is in [error-taxonomy.md](./error-taxonomy.md#sendfailure-inside-sendstatefailed). `SendFailure.humanMessage()` and `Routing.Error.actionableMessage()` / `suggestedAction()` give display text.

## `MeshEvent`

```kotlin
public sealed interface MeshEvent {
    public data class QueueStatusChanged(val status: QueueStatus) : MeshEvent
    public data class Notification(val notification: ClientNotification) : MeshEvent
    public data class CongestionWarning(val metrics: CongestionMetrics) : MeshEvent
    public data object MqttConnected : MeshEvent
    public data class MqttDisconnected(val reason: String? = null) : MeshEvent
    public data class MqttProxyMessage(val message: MqttClientProxyMessage) : MeshEvent
    public data class XmodemPacket(val packet: XModem) : MeshEvent
    public data class FileInfoReceived(val info: FileInfo) : MeshEvent
    public data class LockdownStatusChanged(val status: LockdownStatus) : MeshEvent
    public data class TransportError(val error: MeshtasticException) : MeshEvent
    public data class ProtocolWarning(val message: String, val details: Map<String, Any?> = emptyMap()) : MeshEvent
    public data class IdentityRebound(val previousNodeNum: NodeId, val newNodeNum: NodeId, val reason: String = …) : MeshEvent
    public data class StorageDegraded(val reason: String) : MeshEvent
    public data class KeyVerification(val prompt: KeyVerificationPrompt) : MeshEvent
    public sealed interface SecurityWarning : MeshEvent {
        public data object DuplicatedPublicKey : SecurityWarning
        public data object LowEntropyKey : SecurityWarning
        public data class PublicKeyMismatch(val node: NodeId, val offeredKey: ByteString) : SecurityWarning
    }
    public data class PacketsDropped(val flow: DroppedFlow, val count: Int) : MeshEvent
    public data class DeviceRebooted(val reason: String = "device reported reboot") : MeshEvent
    public data class ExternalConfigChange(val kind: ExternalChangeKind) : MeshEvent
}
public enum class DroppedFlow { Packets, Events }
public enum class ExternalChangeKind { CHANNEL, CONFIG, MODULE_CONFIG }
```

When each variant fires and what to do about it is in [error-taxonomy.md](./error-taxonomy.md#meshevent-reference).

## `NodeChange`

```kotlin
public sealed interface NodeChange {
    public data class Snapshot(val nodes: Map<NodeId, NodeInfo>) : NodeChange
    public data class Added(val node: NodeInfo) : NodeChange
    public data class Updated(val node: NodeInfo, val changed: Set<NodeField>) : NodeChange
    public data class Removed(val nodeId: NodeId) : NodeChange
    public data class WentOffline(val nodeId: NodeId, val lastHeard: Int) : NodeChange
    public data class CameOnline(val nodeId: NodeId) : NodeChange
}

public enum class NodeField { Name, Position, SignalQuality, Battery, Telemetry, DeviceInfo, LastSeen, User, Other }
```

- Every subscriber receives one `Snapshot` first, then deltas in causal order.
- A collector more than 256 changes behind loses the newest ones, reported as `PacketsDropped` on `events` (see [Streams](#streams)). Re-subscribe or use `nodeSnapshot()` to resynchronize.
- `Updated.changed` lists the fields that differ from the previous `NodeInfo`.
- `WentOffline` fires when a node's `last_heard` is older than `Builder.presenceTimeout`; `lastHeard` is that epoch, for "last seen" UI. `CameOnline` fires when the node is heard again.

## `ConnectionState`

```kotlin
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data class Connecting(val attempt: Int) : ConnectionState
    public data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState
    public data object Connected : ConnectionState
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int) : ConnectionState
}

public enum class ConfigPhase { Stage1, Settling, Stage2 }

public val ConnectionState.isUsable: Boolean        // Connected
public val ConnectionState.isInProgress: Boolean    // Connecting, Configuring, Reconnecting
public val ConnectionState.statusMessage: String    // display text
```

`progress` runs from 0 to 1 and doesn't decrease within an attempt. `Connected` is reached only after the final stage's `config_complete_id` matches and the session passkey is seeded ([handshake-fsm.md](./architecture/handshake-fsm.md)).

## Transport specs, identities, and targets

```kotlin
public sealed interface TransportSpec {
    public val identity: TransportIdentity
    public data class Ble(val address: String) : TransportSpec
    public data class Tcp(val host: String, val port: Int = 4403) : TransportSpec
    public data class Http(val baseUrl: String) : TransportSpec
    public data class SerialAndroid(val deviceName: String) : TransportSpec
    public data class SerialJvm(val portName: String) : TransportSpec
}

@JvmInline public value class TransportIdentity(public val raw: String) {
    public companion object { public fun of(spec: TransportSpec): TransportIdentity }
}
```

`TransportSpec` only derives a `TransportIdentity`, the key storage uses. `of` uppercases a BLE address, lowercases a TCP host and an HTTP base URL, and keeps serial names as given, producing `ble:…`, `tcp:host:port`, `http:…`, `serial-android:…`, and `serial-jvm:…`. There is no DNS canonicalization ([ADR-005](./decisions/005-api-shape.md)). `WebSocketTransport` builds its own `ws:` identity; see its [Module.md](../transport-websocket/Module.md).

### Transport targets

```kotlin
public sealed interface TransportTarget {
    public val label: String
    public data class Ble(val needle: String, val scanSeconds: Int = DEFAULT_BLE_SCAN_SECONDS) : TransportTarget
    public data class Tcp(val host: String, val port: Int = DEFAULT_TCP_PORT) : TransportTarget
    public data class Serial(val port: String, val baud: Int = DEFAULT_SERIAL_BAUD) : TransportTarget
    public companion object {
        public const val DEFAULT_TCP_PORT: Int = 4403
        public const val DEFAULT_SERIAL_BAUD: Int = 115_200
        public const val DEFAULT_BLE_SCAN_SECONDS: Int = 8
        public fun parse(spec: String): TransportTarget?
    }
}
```

`parse` reads `ble:NEEDLE`, `tcp:HOST[:PORT]`, and `serial:PORT[:BAUD]`, and returns `null` for an unknown scheme, a missing host or port, or a malformed number. Opening a transport from a target stays in host code. The discovery helpers that go with it:

| Helper | Module | Does |
|---|---|---|
| `BleScanner.scan(): Flow<DiscoveredRadio>` | `transport-ble` | Scans for the Meshtastic service UUID. The host holds the Bluetooth permissions. |
| `bleAdvertisementMatches(needle, name, peripheralName, identifier)` | `transport-ble` | Case-insensitive substring match; a MAC needle also matches a BlueZ object path such as `/org/bluez/hci0/dev_34_B7_DA_62_18_C5`. An empty needle matches everything. |
| `bondedBleDevices(context): List<BondedBleDevice>` | `transport-ble`, Android | The host's bonded devices, without scanning; every bonded device, not only radios. Needs `BLUETOOTH_CONNECT` on API 31+. |
| `JvmSerialPorts.findMeshtastic(): List<SerialPortInfo>` | `transport-serial`, JVM | Ports whose USB vendor ID is in `MESHTASTIC_USB_VENDOR_IDS`. |
| `JvmSerialPorts.byIdPath(portName): String?` | `transport-serial`, JVM | The stable `/dev/serial/by-id/…` path on Linux, else `null`. |
| `TcpDiscovery.discover(): Flow<TcpDiscoveryEvent>` | `transport-tcp` | mDNS browse for `_meshtastic._tcp`; `Found(radio)` and `Lost(serviceName)`. `DiscoveredTcpRadio.toTransport()` opens one. |

## `MeshtasticException`

```kotlin
public sealed class MeshtasticException(message: String, cause: Throwable?) : Exception(message, cause) {
    public var transportIdentity: TransportIdentity?    // set by the SDK; read-only to callers
    public var operation: String?                       // set by the SDK; read-only to callers
    public fun <T : MeshtasticException> T.withContext(transportIdentity: TransportIdentity? = null, operation: String? = null): T

    public class Transport(reason: String, cause: Throwable? = null) : MeshtasticException
    public class Protocol(reason: String, cause: Throwable? = null) : MeshtasticException
    public class HandshakeTimeout(public val stage: String) : MeshtasticException
    public class StorageUnavailable(message: String = "Storage unavailable", cause: Throwable? = null) : MeshtasticException
    public class FirmwareTooOld(public val required: Int, public val present: Int) : MeshtasticException
    public class NotConnected : MeshtasticException
    public class AlreadyConnected : MeshtasticException
    public class PayloadTooLarge(public val maxBytes: Int) : MeshtasticException

    public companion object {
        public fun <T : MeshtasticException> tag(error: T, transportIdentity: TransportIdentity? = null, operation: String? = null): T
    }
}
```

Which call throws which subclass is in [error-taxonomy.md](./error-taxonomy.md#meshtasticexception-hierarchy).

## ID value classes

```kotlin
@JvmInline public value class NodeId(public val raw: Int) {
    public companion object {
        public val LOCAL: NodeId          // 0
        public val BROADCAST: NodeId      // 0xFFFFFFFF
    }
}
@JvmInline public value class ChannelIndex(public val raw: Int)   // 0..MAX_CHANNEL_INDEX (7), else IllegalArgumentException
@JvmInline public value class MessageId(public val raw: Int)

public fun NodeId.toHex(): String                                   // "a1b2c3d4"
public fun NodeId.toDefaultId(): String                             // "!a1b2c3d4"
public fun NodeId.Companion.fromHex(text: String): NodeId?
public fun NodeId.Companion.fromDefaultId(id: String): NodeId?
public fun NodeId.isLocal(own: NodeId? = null): Boolean
```

`isBroadcast()` and `isUnicast()` complete the set.

## `AdminApi`

Getters resolve when the response payload correlates by `request_id`; setters resolve on the routing ACK. A few calls are fire-and-forget and return `Success` once queued, among them `setTime` and `setTimeOnly`. Every call waits up to `Builder.rpcTimeout`, or the timeout given to `withRpcTimeout`. Acked writes go through the paced send queue.

```kotlin
public interface AdminApi {
    public fun forNode(dest: NodeId): AdminApi
    public fun withRpcTimeout(timeout: Duration): AdminApi                     // same target, own timeout

    public suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata>
    public suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config>
    public suspend fun setConfig(config: Config): AdminResult<Unit>
    public suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig>
    public suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit>
    public suspend fun getOwner(): AdminResult<User>
    public suspend fun setOwner(user: User): AdminResult<Unit>
    public suspend fun getChannel(index: ChannelIndex): AdminResult<Channel>
    public suspend fun setChannel(channel: Channel): AdminResult<Unit>
    public suspend fun listChannels(): AdminResult<List<Channel>>

    public suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit>
    public suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit>
    public suspend fun toggleMuted(node: NodeId): AdminResult<Unit>
    public suspend fun removeNode(node: NodeId): AdminResult<Unit>
    public suspend fun setFixedPosition(position: Position): AdminResult<Unit>
    public suspend fun removeFixedPosition(): AdminResult<Unit>
    public suspend fun setHamMode(params: HamParameters): AdminResult<Unit>
    public suspend fun addContact(contact: SharedContact): AdminResult<Unit>
    public suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit>
    public suspend fun lockdown(auth: LockdownAuth): AdminResult<Unit>          // local node only
    public suspend fun setDisplayMirror(enabled: Boolean): AdminResult<Unit>    // local node only; frames on displayFrames
    public suspend fun requestDisplayFrame(): AdminResult<Unit>                 // local node only

    public suspend fun reboot(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun shutdown(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun factoryReset(preserveBleBonds: Boolean = true): AdminResult<Unit>
    public suspend fun nodeDbReset(preserveFavorites: Boolean = true): AdminResult<Unit>
    public suspend fun setTime(at: Instant? = null): AdminResult<Unit>          // null: the builder's clock
    public suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit>

    public suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T>
    public suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T
    public suspend fun playRingtone(): AdminResult<Unit>                       // acked write
    // also: UI config, canned messages, ringtone, connection status, remote hardware pins,
    // preference backups, DFU and OTA, input events, sensor config, scale, simulator exit
}

public sealed interface AdminResult<out T> {
    public data class Success<T>(val value: T) : AdminResult<T>
    public data object SessionKeyExpired : AdminResult<Nothing>
    public data object Unauthorized : AdminResult<Nothing>
    public data object Timeout : AdminResult<Nothing>
    public data object RateLimited : AdminResult<Nothing>
    public data object NodeUnreachable : AdminResult<Nothing>
    public data class Failed(val routingError: Routing.Error) : AdminResult<Nothing>
}
```

`ADMIN_BAD_SESSION_KEY` triggers one automatic retry: the engine re-issues `get_owner_request` to refresh the passkey and replays the call once. A second rejection returns `SessionKeyExpired`. `RateLimited` means the device refused the call for arriving too soon; back off before retrying. The full wire mapping is in [error-taxonomy.md](./error-taxonomy.md#wire-to-adminresult-mapping).

`editSettings` wraps `block` in `begin_edit_settings` and `commit_edit_settings`, so the device applies the writes together. Setters inside `block` (`AdminEdit`: `setConfig`, `setModuleConfig`, `setOwner`, `setChannel`, `setFavorite`, `setIgnored`, `setFixedPosition`, `removeFixedPosition`) return once the paced queue dispatches them; the begin and commit are awaited. `batch` is the throwing counterpart and adds the getters `getConfig`, `getModuleConfig`, and `listChannels` (`AdminBatchScope`), which throw `AdminResultException` on failure. When `block` throws or is cancelled, the SDK still sends `commit_edit_settings` on the same connection, so writes queued before the failure are applied, then rethrows. On a replaced connection it sends nothing.

`AdminResult` helpers follow `kotlin.Result` naming (there's no `kotlin.Result` in the public API): `getOrNull()`, `getOrThrow()` (throws `AdminResultException`), `getOrElse(…)`, `isSuccess()`, `map {}`, `fold(onSuccess, onFailure)`, `onSuccess {}`, `onFailure {}`.

### Config builders

`AdminApi` extensions that read one config section with `getConfig` or `getModuleConfig`, pass it to the block, and write the result with `setConfig` or `setModuleConfig`: eight for `Config` (`setDeviceConfig`, `setPositionConfig`, `setPowerConfig`, `setNetworkConfig`, `setDisplayConfig`, `setLoraConfig`, `setBluetoothConfig`, `setSecurityConfig`) and 15 for `ModuleConfig` (`setMqttConfig`, `setSerialConfig`, `setExternalNotificationConfig`, `setStoreForwardConfig`, `setRangeTestConfig`, `setTelemetryConfig`, `setCannedMessageConfig`, `setAudioConfig`, `setRemoteHardwareConfig`, `setNeighborInfoConfig`, `setAmbientLightingConfig`, `setDetectionSensorConfig`, `setPaxcounterConfig`, `setStatusMessageConfig`, `setTrafficManagementConfig`).

The block's receiver is the section as the targeted device reports it, so fields the block leaves alone keep their values; the device replaces the whole section on a write. A failed read returns its `AdminResult` and writes nothing, and a read whose response lacks the section returns `Failed(NO_RESPONSE)`. On a `forNode` instance both the read and the write go to that node.

```kotlin
client.admin.setLoraConfig {
    newBuilder().also { it.region = Config.LoRaConfig.RegionCode.US }.build()
}
```

The builders extend `AdminApi`, not `AdminEdit`, so they aren't available inside `editSettings { … }`; use `setConfig` there.

### Ham mode

```kotlin
suspend fun RadioClient.enableHamMode(
    callSign: String, shortName: String? = null, longName: String? = null,
    txPower: Int? = null, frequency: Float? = null,
): AdminResult<Unit>
```

`AdminApi.setHamMode(HamParameters)` sends exactly what it's given. Firmware copies `tx_power` and `frequency` into the LoRa config unconditionally, so 0 resets TX power to the regional maximum and clears `override_frequency`. `enableHamMode` fills a null `txPower` or `frequency` from the loaded LoRa config, and returns `AdminResult.Failed(Routing.Error.BAD_REQUEST)` without sending when a value is needed and the config hasn't loaded.

### Device profiles

```kotlin
suspend fun RadioClient.exportDeviceProfile(includePrivateKey: Boolean = false, includeDeviceText: Boolean = true): AdminResult<DeviceProfile>
suspend fun RadioClient.installDeviceProfile(profile: DeviceProfile, installSecurity: Boolean = false): AdminResult<Unit>
```

A `DeviceProfile` holds owner names, a channel share URL, `LocalConfig`, `LocalModuleConfig`, a fixed position, a ringtone, and canned messages. Encode and decode it with `DeviceProfile.ADAPTER`; file I/O is the host's.

Export omits `security.private_key` unless `includePrivateKey` is true. Install writes only the fields the profile has, in one `editSettings` transaction: owner, device, position, power, network, display, Bluetooth, module configs, channels (all eight slots replaced), then the LoRa config when it differs. The fixed position is written after the commit. `security` is skipped unless `installSecurity` is true. `is_licensed`, `traffic_management`, the ringtone, and canned messages aren't installed. A malformed channel URL fails with `Failed(BAD_REQUEST)` before any write.

## `TelemetryApi`

Each `requestX` sends a `Telemetry` holding one empty submessage of the requested variant on `TELEMETRY_APP`, with `want_response = true` and `Data.dest` set, on the node's heard channel, and waits for the reply. Firmware answers only a request that names its variant. `observe(node)` is a cold flow over inbound `packets` filtered by port and sender.

```kotlin
public interface TelemetryApi {
    public suspend fun requestDevice(node: NodeId = NodeId.LOCAL): AdminResult<DeviceMetrics>
    public suspend fun requestEnvironment(node: NodeId = NodeId.LOCAL): AdminResult<EnvironmentMetrics>
    public suspend fun requestPower(node: NodeId = NodeId.LOCAL): AdminResult<PowerMetrics>
    public suspend fun requestAirQuality(node: NodeId = NodeId.LOCAL): AdminResult<AirQualityMetrics>
    public suspend fun requestHealth(node: NodeId = NodeId.LOCAL): AdminResult<HealthMetrics>
    public suspend fun requestHost(node: NodeId = NodeId.LOCAL): AdminResult<HostMetrics>
    public suspend fun requestTrafficManagement(node: NodeId = NodeId.LOCAL): AdminResult<TrafficManagementStats>
    public suspend fun requestLocalStats(): AdminResult<LocalStats>
    public fun observe(node: NodeId): Flow<Telemetry>
}
```

## `RoutingApi`

```kotlin
public interface RoutingApi {
    public suspend fun traceRoute(dest: NodeId, hopLimit: Int = DEFAULT_HOP_LIMIT): AdminResult<RouteDiscovery>   // DEFAULT_HOP_LIMIT = 7
    public suspend fun requestNeighborInfo(node: NodeId = NodeId.LOCAL): AdminResult<NeighborInfo>
}
```

A traceroute across many hops can take longer than the default 30 s `rpcTimeout`.

## `StoreForwardApi`

Talks to store-and-forward (S&F) servers, which hold messages for nodes that were offline. Access it through `client.storeForward`.

```kotlin
public interface StoreForwardApi {
    public val servers: StateFlow<List<NodeId>>
    public suspend fun requestHistory(since: Int? = null, server: NodeId? = null): AdminResult<Int>   // since: epoch seconds
    public suspend fun requestStats(server: NodeId? = null): AdminResult<StoreForwardStats>
    public fun requestLocalHistory(lastRequest: Int, windowMinutes: Int, maxMessages: Int): MessageHandle
    public val events: Flow<StoreForwardEvent>
}

public data class StoreForwardStats(
    val messagesStored: Int, val messagesMax: Int, val uptime: Int,
    val requests: Int, val requestsFailed: Int, val heartbeat: Boolean,
)

public sealed interface StoreForwardEvent {
    public data class ServerDiscovered(val nodeId: NodeId) : StoreForwardEvent
    public data class ServerLost(val nodeId: NodeId) : StoreForwardEvent
    public data class HistoryReplayStarted(val server: NodeId, val messageCount: Int) : StoreForwardEvent
    public data class HistoryReplayComplete(val server: NodeId, val delivered: Int) : StoreForwardEvent
    public data class Heartbeat(val server: NodeId) : StoreForwardEvent
    public data class SfppLinkProvided(val packetId: Int, val from: Int, val to: Int, val messageHash: ByteArray?, val confirmed: Boolean) : StoreForwardEvent
    public data class SfppCanonAnnounced(val messageHash: ByteArray, val rxTime: Long) : StoreForwardEvent
}
```

`requestLocalHistory` sends `CLIENT_HISTORY` to our own node at `BACKGROUND` priority with no ack, no response requested and hop limit `0`, as the Android app does. It reaches a connected node that is an S&F server itself.

### Replay dedupe

A server replays stored text under a fresh packet id, with the stored id in `StoreAndForward.original_id` and the original sender in `MeshPacket.from`. `HistoryReplayComplete.delivered` counts each `(from, original_id)` once. `packets` still carries every replay as received; drop repeats with:

```kotlin
public fun MeshPacket.storeForwardOriginalId(): Int?   // null unless a ROUTER_TEXT_* reply with original_id != 0
public fun Flow<MeshPacket>.dropDuplicateStoreForwardReplays(window: Int = 256): Flow<MeshPacket>
```

Each collection remembers the last `window` keys (replays and plain `TEXT_MESSAGE_APP` packets) and forgets the oldest first.

## `RemoteShellApi`

```kotlin
public interface RemoteShellApi {
    public suspend fun send(node: NodeId, frame: RemoteShell): MessageHandle
    public fun frames(node: NodeId): Flow<RemoteShell>
    public fun newSessionId(): Int                                              // random, non-zero
}
```

`send` PKI-encrypts on channel `0` with the key stored for `node`, without `want_ack`, and throws `MeshtasticException.Protocol` when no key is stored. `frames` decodes inbound `REMOTE_SHELL_APP` packets from `node`. Session state and sequencing belong to the caller.

## `MqttProxyApi`

```kotlin
public interface MqttProxyApi {
    public val messages: Flow<MqttClientProxyMessage>                           // from MeshEvent.MqttProxyMessage
    public suspend fun deliver(message: MqttClientProxyMessage): Boolean        // true once queued
}
```

## `FileTransferApi`

Reads and writes files on the device's filesystem over the phone API with firmware's XModem exchange ([protocol.md §15](./protocol.md#15-file-transfer-xmodem)). Access it through `client.files`. It transfers files only; flashing firmware is out of scope.

```kotlin
public interface FileTransferApi {
    public val progress: StateFlow<FileTransferProgress?>   // null while idle
    public suspend fun download(path: String): FileTransferResult<ByteString>
    public suspend fun upload(path: String, data: ByteString): FileTransferResult<Unit>
    public companion object {
        public const val MAX_FILE_BYTES: Int = 1 shl 20
        public const val MAX_PATH_BYTES: Int = 127
    }
}

public data class FileTransferProgress(
    val path: String, val direction: FileTransferDirection,
    val bytesTransferred: Int, val totalBytes: Int?,   // null for downloads
)

public sealed interface FileTransferResult<out T> {
    public data class Success<T>(val value: T) : FileTransferResult<T>
    public data class Failure(val reason: FileTransferFailure) : FileTransferResult<Nothing>
}

// FileTransferFailure data objects: NotConnected, Busy, InvalidPath, TooLarge, Refused,
// DeviceCancelled, RetriesExhausted, Timeout
```

One transfer runs per client at a time; a second call returns `Busy`. A block that doesn't arrive within 3 s is re-requested, up to 10 times, and a download from a silent device fails within 13.5 s; the `FileTransferApi` KDoc has the worst cases. Cancelling the calling coroutine aborts the transfer. An upload sends `CAN` once the device has acknowledged the request, and firmware deletes the partial file; before that, it re-sends the request to learn whether the device is receiving, and never sends `CAN`. A download sends a frame that makes firmware close the file without deleting it. The transfer reads device frames from `events`; firmware sends one frame and waits for the reply, so the transfer alone doesn't overflow the drop-oldest buffer.

## Packet provenance accessors

Pure extensions over a `MeshPacket` from `packets` and over `NodeInfo`. What each field means and its limits are in [protocol.md, "Provenance fields"](./protocol.md#provenance-fields).

```kotlin
public val MeshPacket.transport: PacketTransport          // Internal, Lora, LoraAlt1..3, Mqtt, MulticastUdp, Api, UnicastUdp, Unrecognized
public val MeshPacket.relayNode: NodeLastByte?            // null when 0 or not a byte
public val MeshPacket.nextHop: NodeLastByte?
public val MeshPacket.signature: PacketSignature          // None, CarriedUnchecked, Malformed, CheckedByDevice
public val MeshPacket.xeddsaSignature: ByteString?
public val MeshPacket.ackProof: AckProof?                 // null off ROUTING_APP; Absent, Valid, Invalid, NoKey, Unrecognized
public val NodeInfo.heardOnCurrentLora: Boolean
public val NodeInfo.signsPackets: Boolean
public fun NodeId.lastByte(): NodeLastByte                // 0x00 maps to 0xFF, as firmware does
public data class NodeLastByte(val value: Int) {          // 1..255
    public fun candidates(nodes: Collection<NodeId>): List<NodeId>
}
```

A `NodeLastByte` isn't a node ID: several nodes can share it, so resolve it with `candidates` against the nodes you know and treat several matches as ambiguous.

## Payload accessors

Extensions on `MeshPacket` that check `decoded.portnum` and decode the payload. Each returns `null` for another port, a packet with no `decoded`, or protobuf bytes that fail to decode; text accessors also return `null` for an empty payload. Raw-bytes ports (`PRIVATE_APP`, `ATAK_FORWARDER`) and `ATAK_PLUGIN_V2`, whose `TAKPacketV2` is zstd-compressed, have no accessor; use `decoded.payload` or `decodeAs(adapter)`.

| Accessor | Port | Returns |
|---|---|---|
| `asText()` | `TEXT_MESSAGE_APP` | `String` |
| `asAlertText()` | `ALERT_APP` | `String` |
| `asRangeTestText()` | `RANGE_TEST_APP` | `String` |
| `asDetectionSensorText()` | `DETECTION_SENSOR_APP` | `String` |
| `asPosition()` | `POSITION_APP` | `Position` |
| `asNodeInfoUser()`, `asNodeInfo()` | `NODEINFO_APP` | `User`, `NodeInfo` |
| `asTelemetry()` | `TELEMETRY_APP` | `Telemetry` |
| `asAdminMessage()` | `ADMIN_APP` | `AdminMessage` |
| `asRouting()` | `ROUTING_APP` | `Routing` |
| `asWaypoint()` | `WAYPOINT_APP` | `Waypoint` |
| `asTraceroute()` | `TRACEROUTE_APP` | `RouteDiscovery` |
| `asNeighborInfo()` | `NEIGHBORINFO_APP` | `NeighborInfo` (proto) |
| `asPaxcount()` | `PAXCOUNTER_APP` | `Paxcount` |
| `asRemoteHardware()` | `REMOTE_HARDWARE_APP` | `HardwareMessage` |
| `asNodeStatus()` | `NODE_STATUS_APP` | `StatusMessage` |
| `asMapReport()` | `MAP_REPORT_APP` | `MapReport` |
| `asMeshBeacon()` | `MESH_BEACON_APP` | `MeshBeacon` |
| `asRemoteShell()` | `REMOTE_SHELL_APP` | `RemoteShell` |
| `asPowerStress()` | `POWERSTRESS_APP` | `PowerStressMessage` |
| `asKeyVerification()` | `KEY_VERIFICATION_APP` | `KeyVerification` |
| `asAtakPacket()` | `ATAK_PLUGIN` | `TAKPacket` |

Store-and-forward traffic (`STORE_FORWARD_APP`, `STORE_FORWARD_PLUSPLUS_APP`) surfaces on `StoreForwardApi.events` instead.

## LoRa regions and frequency slots

Pure functions in `org.meshtastic.sdk` that answer what a `Config.LoRaConfig` means on the air: how many frequency slots the band has, which slot the primary channel uses, and the center frequency. No engine state and no I/O; float math is single-precision, as on the device.

```kotlin
public data class LoRaRegionInfo(
    val region: RegionCode, val freqStartMHz: Float, val freqEndMHz: Float, val dutyCyclePercent: Float,
    val powerLimitDbm: Int, val freqSwitching: Boolean, val wideLora: Boolean, val spacingMHz: Float,
    val paddingMHz: Float, val audioPermitted: Boolean, val licensedOnly: Boolean,
    val defaultPreset: ModemPreset, val overrideSlot: Int,
) {
    companion object {
        val all: List<LoRaRegionInfo>                      // firmware order, UNSET last
        fun forRegion(code: RegionCode): LoRaRegionInfo    // no row: the UNSET row, as firmware does
    }
}

public data class ModemParams(val bandwidthKHz: Float, val spreadFactor: Int, val codingRate: Int)
public fun ModemPreset.modemParams(wideLora: Boolean = false): ModemParams

public object LoRaFrequency {
    fun bandwidthKHzForCode(code: Int): Float
    fun slotWidthMHz(region: LoRaRegionInfo, bandwidthKHz: Float): Float
    fun numChannels(region: LoRaRegionInfo, bandwidthKHz: Float): Int
    fun resolveSlot(region: LoRaRegionInfo, numChannels: Int, channelName: String, channelNum: Int = 0): Int?
    fun hashedChannelName(channelName: String, usePreset: Boolean, preset: ModemPreset): String
    fun centerFrequencyMHz(region: LoRaRegionInfo, bandwidthKHz: Float, slot: Int, frequencyOffsetMHz: Float = 0f): Float
    fun plan(config: Config.LoRaConfig, primaryChannelName: String): LoRaFrequencyPlan
}

public data class LoRaFrequencyPlan(
    val region: LoRaRegionInfo, val bandwidthKHz: Float, val numChannels: Int, val slot: Int?, val frequencyMHz: Float?,
)
```

```kotlin
val plan = LoRaFrequency.plan(lora, primaryChannelName = channel.settings.name)
// US, LONG_FAST, name "LongFast": numChannels = 104, slot = 20, frequencyMHz = 906.875
```

- Slots are 1-based, like `LoRaConfig.channel_num`. `slot` is `null` with `override_frequency` set or when the band has no slots; `frequencyMHz` is then the override plus `frequency_offset`, or `null` without one.
- An `UNSET` region, and any `RegionCode` firmware has no row for, resolves to the `UNSET` row: zero slots, no slot, and no frequency unless `override_frequency` is set.
- `numChannels` isn't clamped to one. A bandwidth far wider than the band rounds to 0; `plan` avoids that for `use_preset = false` by substituting the region default preset's bandwidth, as firmware does.
- A preset the region doesn't offer isn't substituted. The device clamps it when the config is applied, so a stored config is already legal.
- A caller holding region data from elsewhere can build a `LoRaRegionInfo` and pass it to the `LoRaFrequency` functions directly.

## Storage

```kotlin
public interface StorageProvider {
    public suspend fun activate(identity: TransportIdentity): DeviceStorage
}

public interface DeviceStorage : AutoCloseable {
    public suspend fun loadNodes(): Map<NodeId, NodeInfo>
    public suspend fun saveNode(node: NodeInfo)
    public suspend fun removeNode(nodeId: NodeId)
    public suspend fun loadConfig(): ConfigBundle?
    public suspend fun saveConfig(config: ConfigBundle)
    public suspend fun loadChannels(): List<Channel>
    public suspend fun saveChannels(channels: List<Channel>)
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String)
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String, deviceId: ByteString?)   // default delegates to the overload above
    public suspend fun loadOwnDeviceId(): ByteString?                                                  // default returns null
    public suspend fun clear()
    public suspend fun saveSessionPasskey(passkey: SessionPasskey)
    public suspend fun loadSessionPasskey(): SessionPasskey?
    public suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long)
    public suspend fun loadHeartbeats(): Map<NodeId, Long>
}

public data class ConfigBundle(
    val myInfo: MyNodeInfo,
    val metadata: DeviceMetadata,
    val configs: List<Config>,
    val moduleConfigs: List<ModuleConfig>,
    val deviceUIConfig: DeviceUIConfig? = null,
    public val regionPresets: LoRaRegionPresetMap? = null,   // session-only, never stored
) {
    public val capabilities: DeviceCapabilities               // firmware version + excluded_modules
}

public class InMemoryStorage : DeviceStorage               // RAM only
public class InMemoryStorageProvider : StorageProvider
```

`recordOwnNode` is the factory-reset detector. On a NodeNum that differs from the one stored for this identity, an implementation clears its state and records the new tuple. With the three-argument overload and an equal stored `device_id`, the device renumbered: the implementation keeps its state, removes the old own-node row, and records the new tuple. The engine reports both as `MeshEvent.IdentityRebound` ([storage.md](./architecture/storage.md#identity-rebind-detection-s-p0-2)).

`InMemoryStorageProvider` suits one-shot tools, gateways that rebuild state from the handshake, and web targets. `:storage-sqldelight` is the persistent backend on JVM, Android, and iOS.

## Transport

```kotlin
public interface RadioTransport {
    public val identity: TransportIdentity
    public val linkDropsOnRestartCommands: Boolean get() = false   // true for BLE: firmware drops it in restart handlers
    public suspend fun connect()
    public suspend fun disconnect()
    public suspend fun send(frame: Frame)
    public fun frames(): Flow<Frame>
    public val state: StateFlow<TransportState>
}

public sealed interface TransportState {
    public data object Disconnected : TransportState
    public data object Connecting : TransportState
    public data object Bonding : TransportState           // BLE pairing in progress
    public data object Connected : TransportState
    public data class Error(val cause: Throwable, val recoverable: Boolean) : TransportState
}

public data class Frame(public val bytes: ByteString)
```

Frames carry the 4-byte stream header on every transport; `WebSocketTransport` strips and restores it at the socket.

| Transport | Construct with |
|---|---|
| TCP | `TcpTransport(host, port)` |
| BLE | `BleTransport(radio)` from `BleScanner.scan()`, on every target; on Android also `BleTransport(address, resilience, builderAction)` and `BleTransport(device: BondedBleDevice, …)` |
| Serial, JVM | `JvmSerialPorts.open(portName, baudRate)` |
| Serial, Android | `AndroidSerialPorts.open(portName, baudRate)` after `AndroidSerialPorts.init(context)`, or `AndroidUsbSerialPorts.open(context, deviceName)` |
| WebSocket | `WebSocketTransport(url, httpClient = null)` for `ws://` or `wss://`; the path defaults to `/api/v1/ws` |
| Tests | `FakeRadioTransport(identity, frames, autoHandshake, nodeNum)` |

Each serial `open` has an overload taking `onTextLine: (String) -> Unit`, which receives the text the device prints between frames. `BleResilience` (Android) turns on bond-first, an `autoConnect` fallback, and a stale GATT cache refresh. `WebSocketTransport`'s close codes, contested-slot handling, and browser constraints are in its [Module.md](../transport-websocket/Module.md).

## Logging

```kotlin
public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, cause: Throwable?)
    public companion object { public val Silent: LogSink }
}
public enum class LogLevel { NONE, VERBOSE, DEBUG, INFO, WARN, ERROR }

public interface PayloadRedactor {
    public fun describe(fromRadio: FromRadio): String
    public fun describe(toRadio: ToRadio): String
    public companion object { public val Default: PayloadRedactor }
}
```

`LogLevel.NONE` is the off state of `Builder.protocolLogging(...)`. The SDK doesn't depend on Kermit, Timber, or SLF4J. See [observability.md](./observability.md).

## Related

- [ADR-005](./decisions/005-api-shape.md): design rationale
- [error-taxonomy.md](./error-taxonomy.md): wire-to-Kotlin error mapping
- [versioning.md](./versioning.md): ABI policy
- [glossary.md](./glossary.md): vocabulary
