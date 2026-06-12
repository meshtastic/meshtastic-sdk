# Public API reference

> A flat, browseable view of every public symbol shipped by `:core`. Authoritative source remains [`./SPEC.md`](./SPEC.md) §3; this document tracks it 1:1 with KDoc-shaped commentary suitable for the Dokka pipeline once the code lands.
>
> All payload-bearing types named without a package prefix (`MeshPacket`, `NodeInfo`, `Config`, `User`, `Channel`, `AdminMessage`, `Routing.Error`, …) are **Wire-generated types from `org.meshtastic.proto.*`**, per [ADR-001](./decisions/001-public-api-uses-generated-protobufs.md). The SDK does not redeclare them.

## Package map

| Package | Stability | Contents |
|---|---|---|
| `org.meshtastic.sdk` | Public | `RadioClient`, `MessageHandle`, `SendState`, `SendFailure`, `SendOutcome`, `MeshEvent`, `DroppedFlow`, `NodeChange`, `NodeField`, `ConnectionState`, `ConfigPhase`, `TransportSpec`, `TransportIdentity`, `RadioTransport`, `TransportState`, `Frame`, `MeshtasticException`, `NodeId`, `ChannelIndex`, `MessageId`, `LogSink`, `LogLevel`, `PayloadRedactor`, `StorageProvider`, `DeviceStorage`, `ConfigBundle`, `KeyVerificationPrompt`, `AdminApi`, `AdminResult`, `TelemetryApi`, `RoutingApi`, `StoreForwardApi`, `StoreForwardStats`, `StoreForwardEvent`, `Clock`, `Constants`, `SessionPasskey` |
| `org.meshtastic.sdk.transport.tcp` | Public | `TcpTransport` |
| `org.meshtastic.sdk.transport.ble` | Public | `BleTransport` |
| `org.meshtastic.sdk.transport.serial` | Public | `AndroidSerialPorts` (Android), `JvmSerialPorts` (JVM) |
| `org.meshtastic.sdk.storage.sqldelight` | Public | `SqlDelightStorageProvider`, `AndroidContextHolder` (Android) |
| `org.meshtastic.sdk.testing` | Public (test fixtures) | `FakeRadioTransport`, `InMemoryStorage`, `InMemoryStorageProvider` |
| `org.meshtastic.proto.*` | Public (re-export) | All Wire-generated protobuf types |
| `org.meshtastic.sdk.internal.*` | Internal (Kotlin `internal` modifier) | Engine, FSM, codec — not part of API surface |

> All public top-level types in `org.meshtastic.sdk` are considered stable for the 0.1.x series.

## API conventions

Deliberate, enforced choices about the shape of the public surface:

- **Proto types are exposed unwrapped** ([ADR-001](./decisions/001-public-api-uses-generated-protobufs.md)).
  The SDK's ABI therefore tracks the `org.meshtastic:protobufs` artifact; proto bumps are priced
  into the SemVer table in [`versioning.md`](./versioning.md).
- **One byte-string vocabulary: `okio.ByteString`.** Wire-generated proto fields use it
  (Wire's runtime type), so it is unavoidable in the surface — and therefore SDK-native types
  (`Frame`, `SessionPasskey`, the `send(portnum, payload)` overload) use it too, rather than
  introducing a second vocabulary. `kotlinx-io` is deliberately **not** a dependency.
- **No blocking bridges.** Lifecycle is suspend-only (`connect()`/`disconnect()`); there is
  deliberately no `AutoCloseable`/`close()` on `RadioClient`. Blocking teardown invites ANR on
  Android main and deadlock on iOS main.
- **Data classes in the public API are a deliberate trade.** Event/result types are `data` so
  consumers get structural equality in tests and exhaustive `when` matching. The ABI cost
  (`copy`/`componentN` lock-in) is mitigated by the pre-1.0 window, the CI-gated Kotlin ABI
  dumps (every signature change is reviewed), and the SemVer pricing of sealed-subtype
  additions. When the [Poko](https://github.com/drewhamilton/Poko) compiler plugin supports the
  current Kotlin version, event/result types migrate to `@Poko` classes (tracked in
  [`roadmap.md`](./roadmap.md)) — consumers should treat `copy()`/destructuring of SDK types as
  unstable conveniences, not contract.
- **Kotlin/Swift first.** No `@JvmOverloads`/`@JvmName` Java-ergonomics annotations; Java
  callers are not a supported audience. Swift interop is first-class via SKIE + `@Throws`.


### Coordinates and the BOM

Every artifact above is published under the `org.meshtastic` group as
`sdk-<module>` (e.g. `org.meshtastic:sdk-core`).
**Use [`org.meshtastic:sdk-bom`](../bom/README.md) to align
versions across modules** — see [`bom/README.md`](../bom/README.md) for
the full pinned-artifact table and Gradle / Maven snippets, and
[`docs/versioning.md`](./versioning.md#bom) for the versioning policy.

## `RadioClient`

The single public entry point. One `RadioClient` ↔ one radio (ADR-005 multi-radio rule).

### Construction

```kotlin
RadioClient.Builder()
    .transport(TcpTransport(host = "meshtastic.local", port = 4403))   // required
    .storage(SqlDelightStorageProvider(baseDir = "/var/data"))         // required, no default
    .logger(MyLogSink)                                                 // default: LogSink.Silent
    .clock(Clock.System)                                               // default
    .coroutineContext(SupervisorJob() + Dispatchers.Default)           // default: Dispatchers.Default
    .autoSyncTimeOnConnect(true)                                       // default: true
    .disableBleHeartbeat(false)                                        // default: false (BLE only)
    .protocolLogging(LogLevel.NONE, PayloadRedactor.Default)           // default: NONE
    .sendTimeout(30.seconds)                                           // default: 30 s — applies to MessageHandle.await()
    .rpcTimeout(10.seconds)                                            // default: 10 s — per-op admin RPC timeout
    .presenceTimeout(15.minutes)                                       // default: 15 min — node offline threshold
    .autoReconnect(AutoReconnectConfig())                              // default: disabled; opt-in exponential backoff
    .build()
```

`Builder.transport(spec: TransportSpec)` is also available for spec-driven setups, but you must additionally provide a concrete `RadioTransport` — see `samples/cli` for a `TransportSpec → RadioTransport` opener pattern.

#### `AutoReconnectConfig` *(since 0.2.0)*

```kotlin
public data class AutoReconnectConfig(
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 60.seconds,
    val multiplier: Double = 2.0,
    val jitterFactor: Double = 0.15,
    val maxAttempts: Int = Int.MAX_VALUE,
)
```

When enabled, the engine automatically reconnects on transport drops, transitioning through `ConnectionState.Reconnecting(cause, attempt)`. Disable by omitting the `.autoReconnect(...)` builder call.

### Lifecycle

| Member | Returns | Throws | Notes |
|---|---|---|---|
| `connect()` | `Unit` (suspends until `Connected`) | `MeshtasticException` (`AlreadyConnected`, `Transport`, `Protocol`, `StorageUnavailable`, `HandshakeTimeout`, `FirmwareTooOld`), `CancellationException` | **Not** idempotent — calling `connect()` while already `Connected` throws `AlreadyConnected` (deliberate; silent no-op hides reconnect-loop bugs). |
| `connectAndAwaitReady()` | `Unit` (suspends until `Connected` with session passkey seeded) | same as `connect()` | Convenience wrapper: calls `connect()`, then awaits the post-handshake admin session passkey seed. Prefer this unless you need to observe handshake progress. |
| `disconnect()` | `Unit` | never | Idempotent. Cancels supervisor; resolves all open `MessageHandle`s to `Failed(Disconnected)`. |
| `connection: StateFlow<ConnectionState>` | — | — | Conflated. Ordering: `Disconnected → Connecting → Configuring* → Connected`; on drop: `Connected → Reconnecting → Connecting → …`. |
| `ownNode: StateFlow<NodeInfo?>` | — | — | `null` until handshake completes. After: always populated; updated on `node_info` for our own `NodeNum`. |
| `configBundle: StateFlow<ConfigBundle?>` | — | — | `null` until Stage 1 completes. Contains `MyNodeInfo`, `DeviceMetadata`, configs, module configs, and `DeviceUIConfig`. Updated on unsolicited config pushes. |
| `channels: StateFlow<List<Channel>>` | — | — | Empty until Stage 1 completes. Reactive view of all device channels. Updated on `setChannel` responses. |

### Streams

| Member | Backing | Buffer / overflow |
|---|---|---|
| `nodes: Flow<NodeChange>` | custom snapshot+delta | 256 / `SUSPEND` |
| `packets: Flow<MeshPacket>` | `MutableSharedFlow(replay=0)` | 128 / `SUSPEND` |
| `events: Flow<MeshEvent>` | `MutableSharedFlow(replay=0)` | 64 / `DROP_OLDEST` |

### Outbound

| Member | Returns | Throws |
|---|---|---|
| `send(packet: MeshPacket): MessageHandle` | handle (state already `Queued`) | `NotConnected`, `PayloadTooLarge` |
| `sendText(text: String, channel: ChannelIndex = ChannelIndex(0), to: NodeId = NodeId.BROADCAST): MessageHandle` | handle | same as `send` |
| `sendReaction(emoji: String, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), replyId: Int): MessageHandle` | handle | same as `send` |
| `sendRaw(frame: ToRadio)` | `Unit` | `NotConnected` |
| `requestNodeInfo(node: NodeId): AdminResult<NodeInfo>` | result | `NotConnected` |
| `nodeSnapshot(): Map<NodeId, NodeInfo>` | snapshot | `NotConnected` |

### Send DSL *(since 0.2.0)*

```kotlin
val handle = client.send {
    text("hello mesh")
    to(NodeId.BROADCAST)
    channel(ChannelIndex(0))
    wantAck(true)
}
```

The `send { … }` lambda configures a `MeshPacket` builder. Available DSL methods: `text(String)`, `data(PortNum, ByteArray)`, `to(NodeId)`, `channel(ChannelIndex)`, `wantAck(Boolean)`, `hopLimit(Int)`, `priority(Priority)`.

### Sub-API namespaces

`client.admin: AdminApi`, `client.telemetry: TelemetryApi`, `client.routing: RoutingApi`, `client.storeForward: StoreForwardApi` are fully implemented and available while the client is in the `Connected` state. Each is lazily initialized on first access. See the respective interface definitions below for method inventories.

## `MessageHandle`

```kotlin
public class MessageHandle internal constructor(
    public val id: MessageId,
    public val state: StateFlow<SendState>,
) {
    public suspend fun await(): SendOutcome
    public fun cancel()
}
```

### Invariants (per [ADR-005](./decisions/005-api-shape.md))

1. **Disconnect resolves all open handles** to `Failed(Disconnected)` before transport teardown completes. `await()` returns the corresponding outcome — no leaks.
2. **Caller-cancel is independent of handle.** Cancelling the coroutine inside `await()` rethrows `CancellationException`; the handle's `state` continues to update for other observers. Use `cancel()` to actively withdraw.
3. **`cancel()` is idempotent and state-dependent.** Pre-`Sent`: removed from outbound queue, `state = Failed(Cancelled)`. Post-`Sent`: no effect on the radio; state unchanged. Always safe.
4. **`PayloadTooLarge` is never observed via `MessageHandle`.** It's thrown synchronously by `send()`; no handle is returned.

## `SendState`, `SendFailure`, `SendOutcome`

```kotlin
public sealed interface SendState {
    public data object Queued : SendState
    public data object Sent : SendState           // device QueueStatus saw mesh_packet_id
    public data object Acked : SendState          // unicast routing ACK heard
    public data object Delivered : SendState      // broadcast: rebroadcast heard
    public data class Failed(val reason: SendFailure) : SendState
}

public sealed interface SendFailure {
    public data object NoRoute : SendFailure                    // Routing.NO_ROUTE
    public data object MaxRetransmit : SendFailure
    public data object Timeout : SendFailure
    public data object DutyCycleLimit : SendFailure
    public data object Disconnected : SendFailure               // transport dropped mid-send
    public data object Cancelled : SendFailure                  // MessageHandle.cancel() called pre-Sent
    public data object IdCollision : SendFailure                // outbound packet id clashed with an in-flight one
    public data object AckTimeout : SendFailure                 // ACK / DELIVERED never observed within Builder.sendTimeout
    public data object HandshakeFailed : SendFailure            // send attempted while handshake was unwinding
    public data class Other(val routingError: Routing.Error) : SendFailure
    public data class Unknown(val message: String) : SendFailure
}
// Separate sealed type — NOT a typealias for SendState.
public sealed interface SendOutcome {
    public data object Success : SendOutcome
    public data class Failure(val reason: SendFailure) : SendOutcome
}
```

`MessageHandle.await()` collapses the `SendState` machine to a `SendOutcome`: any of `Acked`/`Delivered` becomes `SendOutcome.Success`; `Failed(reason)` becomes `SendOutcome.Failure(reason)`. `PayloadTooLarge` is intentionally absent — see invariant 4 above; it's thrown synchronously by `send()`.

## `MeshEvent`

```kotlin
public sealed interface MeshEvent {
    public data class QueueStatusChanged(val status: QueueStatus) : MeshEvent
    public data class Notification(val notification: ClientNotification) : MeshEvent
    public data class TransportError(val error: MeshtasticException.Transport) : MeshEvent
    public data class ProtocolWarning(val message: String, val details: Map<String, String> = emptyMap()) : MeshEvent
    public data class KeyVerification(val prompt: KeyVerificationPrompt) : MeshEvent
    public data class PacketsDropped(val flow: DroppedFlow, val count: Int) : MeshEvent
    public data class IdentityRebound(val previous: NodeId, val current: NodeId) : MeshEvent
    public data class StorageDegraded(val cause: MeshtasticException.StorageUnavailable) : MeshEvent
    public data class DeviceRebooted(val rebootCount: Int) : MeshEvent
    public data class CongestionWarning(val freeSlots: Int, val totalSlots: Int) : MeshEvent
    public data class ExternalConfigChange(val config: Config) : MeshEvent
    public sealed interface SecurityWarning : MeshEvent {
        public data class DuplicatedPublicKey(val nodeId: NodeId) : SecurityWarning
        public data class LowEntropyKey(val nodeId: NodeId) : SecurityWarning
    }
}
public enum class DroppedFlow { Packets, Events }
```

`ProtocolWarning` is non-fatal advisory ("skipped malformed envelope", etc.); the optional `details` map carries structured context (e.g., `"packet_id" to "0x1234"`). `IdentityRebound` is the dedicated signal for the engine clearing storage after a NodeNum change (see [storage.md §"Consumer-observable signal (R-9)"](./architecture/storage.md#consumer-observable-signal-r-9)). `PacketsDropped` is the only observability hook for backpressure-induced loss; emitted at most once per drop burst. `StorageDegraded` fires when the storage backend transitions to read-through-only mode. `CongestionWarning` fires when the device's outbound queue is critically full. `ExternalConfigChange` fires when an unsolicited admin message from firmware updates the local config state. `SecurityWarning.*` flag suspect key material observed in inbound `node_info` payloads.

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

public enum class NodeField {
    Name, Position, SignalQuality, Battery, Telemetry, DeviceInfo, LastSeen, User, Other,
}
```

Contract:
- Every new subscriber receives exactly one `Snapshot` first, then deltas.
- Deltas MUST NOT drop (`SUSPEND` overflow on the backing flow).
- `Updated.changed` is the *minimal* set of fields whose value differs from the prior `NodeInfo`. Useful for diffing UI state without re-rendering everything.

### Presence tracking *(since 0.2.0)*

`WentOffline` and `CameOnline` are emitted by the engine when presence tracking is enabled via `Builder.presenceTimeout()`. `WentOffline.lastHeard` is the node's most recent `last_heard` epoch, allowing the UI to display "last seen" timestamps. A node transitions back to online when it sends any new packet.

## `ConnectionState`

```kotlin
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data class Connecting(val attempt: Int) : ConnectionState
    public data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState
    public data object Connected : ConnectionState
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int) : ConnectionState
}

public enum class ConfigPhase {
    /**
     * First handshake stage: metadata, device config, and channels.
     *
     * Device sends: `my_info`, `device_metadata`, config payloads for all `ConfigType`s,
     * and channel definitions. Typical duration: 1-5 seconds depending on channel count.
     */
    Stage1,

    /**
     * Settling period between Stage 1 and Stage 2.
     *
     * Brief pause to allow device to prepare node database. Typical duration: 0.5-1 second.
     */
    Settling,

    /**
     * Second handshake stage: node database.
     *
     * Device sends cached `node_info` payloads for all known nodes. Duration scales with mesh size
     * (large meshes may take 5-10+ seconds). Completes when device emits `config_complete_id`.
     */
    Stage2,
}
```

`progress: 0f..1f` is monotonically non-decreasing within an attempt. `Connected` is reached **only** after Stage 2's `config_complete_id` matches and the `session_passkey` is seeded ([handshake-fsm](./architecture/handshake-fsm.md)).

### Extension properties *(since 0.2.0)*

```kotlin
/** True for Connected — the client can send/receive. */
public val ConnectionState.isUsable: Boolean

/** True for Connecting, Configuring, Reconnecting — a transition is in progress. */
public val ConnectionState.isInProgress: Boolean

/** Human-readable status suitable for UI display (e.g., "Configuring (Stage 1 — 45%)"). */
public val ConnectionState.statusMessage: String
```

## `TransportSpec` and `TransportIdentity`

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
    public companion object {
        public fun of(spec: TransportSpec): TransportIdentity = when (spec) {
            is TransportSpec.Ble -> TransportIdentity("ble:${spec.address.uppercase()}")
            is TransportSpec.Tcp -> TransportIdentity("tcp:${spec.host.lowercase()}:${spec.port}")
            is TransportSpec.Http -> TransportIdentity("http:${spec.baseUrl.lowercase()}")
            is TransportSpec.SerialAndroid -> TransportIdentity("serial-android:${spec.deviceName}")
            is TransportSpec.SerialJvm -> TransportIdentity("serial-jvm:${spec.portName}")
        }
    }
}
```

Identity normalisation: BLE address uppercased (canonical MAC form); TCP host + HTTP base URL lowercased; serial device names echo input. **No DNS canonicalisation.** See ADR-005's fragmentation caveat.

## `MeshtasticException`

```kotlin
public sealed class MeshtasticException(message: String, cause: Throwable? = null)
    : Exception(message, cause) {

    /** The transport identity active when the exception was created (null if pre-connect). */
    public open val transportIdentity: TransportIdentity? = null

    /** The high-level operation that was in progress (e.g., "connect", "sendText", "admin.setConfig"). */
    public open val operation: String? = null

    public class Transport(reason: String, cause: Throwable? = null) : MeshtasticException(reason, cause)
    public class Protocol(reason: String) : MeshtasticException(reason)
    public class StorageUnavailable(message: String = "Storage unavailable", cause: Throwable? = null) : MeshtasticException(message, cause)
    public class FirmwareTooOld(public val required: Int, public val present: Int)
        : MeshtasticException("Firmware requires newer client (need $required, have $present)")
    public class NotConnected : MeshtasticException("Client not connected")
    public class AlreadyConnected : MeshtasticException("Client already connected")
    public class PayloadTooLarge(public val maxBytes: Int) : MeshtasticException("Payload exceeds $maxBytes bytes")
    public class HandshakeTimeout(public val stage: String)
        : MeshtasticException("Handshake timed out in $stage")

    public companion object {
        /** Create a tagged exception for structured logging: `MeshtasticException.tag("engine.handshake")`. */
        public fun tag(operation: String): String = operation
    }
}
```

Full mapping from wire-level errors to exceptions vs results lives in [`error-taxonomy.md`](./error-taxonomy.md).

## ID value classes

```kotlin
@JvmInline public value class NodeId(public val raw: Int) {
    public companion object {
        public val LOCAL: NodeId = NodeId(0)
        public val BROADCAST: NodeId = NodeId(0xFFFFFFFF.toInt())
    }
    public val shortForm: String get() = "!" + raw.toUInt().toString(radix = 16).padStart(8, '0')
}
@JvmInline public value class ChannelIndex(public val raw: Int) {
    init { require(raw in 0..MAX_CHANNEL_INDEX) }
    public companion object { public const val MAX_CHANNEL_INDEX: Int = 7 }
}
@JvmInline public value class MessageId(public val raw: Int)
```

## `AdminApi` *(Phase 2)*

Each method maps onto a single `AdminMessage` round-trip with the device. Setters resolve when the engine sees the wire-level routing ACK; getters resolve when the dispatcher correlates the response payload by `request_id`. `SessionKeyExpired` triggers a single-shot retry: a fresh `get_owner_request` re-seeds the session passkey, then the original call is replayed once.

```kotlin
public interface AdminApi {
    /** Returns a copy of this AdminApi that targets a remote node. All subsequent calls route to [dest]. @since 0.2.0 */
    public fun forNode(dest: NodeId): AdminApi

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

    public suspend fun reboot(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun shutdown(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun factoryReset(preserveBleBonds: Boolean = true): AdminResult<Unit>
    public suspend fun nodeDbReset(): AdminResult<Unit>

    /** See pitfall §19.17. `autoSyncTimeOnConnect=true` calls this once post-handshake on >60s skew. */
    public suspend fun setTime(at: Instant = Clock.System.now()): AdminResult<Unit>

    /** Batches multiple writes inside `begin_edit_settings` / `commit_edit_settings`. */
    public suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T>
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

`SessionKeyExpired` triggers an automatic single retry inside the engine: the engine re-issues `get_owner_request` to refresh `session_passkey`, then replays the original admin call once. If the retry also returns `SessionKeyExpired`, the result surfaces unmodified. `RateLimited` indicates the device rejected the call due to rate limiting (`Routing.Error.RATE_LIMIT_EXCEEDED`); callers should back off before retrying.

### `AdminResult` extensions *(since 0.2.0)*

```kotlin
public fun <T> AdminResult<T>.getOrNull(): T?
public fun <T> AdminResult<T>.getOrThrow(): T
public fun <T, R> AdminResult<T>.map(transform: (T) -> R): AdminResult<R>
public fun <T, R> AdminResult<T>.fold(onSuccess: (T) -> R, onFailure: (AdminResult<Nothing>) -> R): R
public fun <T> AdminResult<T>.onSuccess(action: (T) -> Unit): AdminResult<T>
public fun <T> AdminResult<T>.onFailure(action: (AdminResult<Nothing>) -> Unit): AdminResult<T>
```

These follow the same naming conventions as `kotlin.Result` extensions but operate on the `AdminResult` sealed type (per ADR-005: no `kotlin.Result<T>` in the public API).

## `TelemetryApi` *(Phase 2)*

Each `requestX` sends an empty `Telemetry` packet on `TELEMETRY_APP` with `want_response = true` and waits for the matching reply. `observe(node)` is a cold flow over the engine's inbound `packets` filtered by portnum + origin.

```kotlin
public interface TelemetryApi {
    public suspend fun requestDevice(node: NodeId = NodeId.LOCAL): AdminResult<DeviceMetrics>
    public suspend fun requestEnvironment(node: NodeId = NodeId.LOCAL): AdminResult<EnvironmentMetrics>
    public suspend fun requestPower(node: NodeId = NodeId.LOCAL): AdminResult<PowerMetrics>
    public suspend fun requestAirQuality(node: NodeId = NodeId.LOCAL): AdminResult<AirQualityMetrics>
    public suspend fun requestLocalStats(): AdminResult<LocalStats>

    /** Cold flow of telemetry packets observed for [node]. Single-shot — completes when the flow is cancelled. */
    public fun observe(node: NodeId): Flow<Telemetry>
}
```

## `RoutingApi` *(Phase 2)*

```kotlin
public interface RoutingApi {
    public suspend fun traceRoute(dest: NodeId, hopLimit: Int = 7): AdminResult<RouteDiscovery>
    public suspend fun requestNeighborInfo(node: NodeId = NodeId.LOCAL): AdminResult<NeighborInfo>
}
```

## `StoreForwardApi` *(since 0.2.0)*

API for interacting with Store-and-Forward (S&F) nodes on the mesh. S&F nodes temporarily store messages for offline nodes and deliver them when the target comes back online. Access via `client.storeForward`.

```kotlin
public interface StoreForwardApi {
    /** Known S&F server nodes on the mesh. Updated reactively when nodes advertise capability. */
    public val servers: StateFlow<List<NodeId>>

    /** Request delivery of stored messages since [since] (epoch seconds). */
    public suspend fun requestHistory(since: Int? = null, server: NodeId? = null): AdminResult<Int>

    /** Query statistics from a S&F server. */
    public suspend fun requestStats(server: NodeId? = null): AdminResult<StoreForwardStats>

    /** Flow of S&F-specific events (heartbeats, replays, SFPP link/canon). */
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

## Config Builder Extensions *(since 0.2.0)*

Extension functions on `AdminApi` that build and send config protos in a single call, using Kotlin `copy {}` semantics:

```kotlin
// Config types (8 extensions)
suspend fun AdminApi.setDeviceConfig(block: Config.DeviceConfig.() -> Config.DeviceConfig): AdminResult<Unit>
suspend fun AdminApi.setPositionConfig(block: Config.PositionConfig.() -> Config.PositionConfig): AdminResult<Unit>
suspend fun AdminApi.setPowerConfig(block: Config.PowerConfig.() -> Config.PowerConfig): AdminResult<Unit>
suspend fun AdminApi.setNetworkConfig(block: Config.NetworkConfig.() -> Config.NetworkConfig): AdminResult<Unit>
suspend fun AdminApi.setDisplayConfig(block: Config.DisplayConfig.() -> Config.DisplayConfig): AdminResult<Unit>
suspend fun AdminApi.setLoraConfig(block: Config.LoRaConfig.() -> Config.LoRaConfig): AdminResult<Unit>
suspend fun AdminApi.setBluetoothConfig(block: Config.BluetoothConfig.() -> Config.BluetoothConfig): AdminResult<Unit>
suspend fun AdminApi.setSecurityConfig(block: Config.SecurityConfig.() -> Config.SecurityConfig): AdminResult<Unit>

// Module config types (15 extensions)
suspend fun AdminApi.setMqttConfig(block: ModuleConfig.MQTTConfig.() -> ModuleConfig.MQTTConfig): AdminResult<Unit>
suspend fun AdminApi.setSerialConfig(block: ModuleConfig.SerialConfig.() -> ModuleConfig.SerialConfig): AdminResult<Unit>
suspend fun AdminApi.setExternalNotificationConfig(block: ModuleConfig.ExternalNotificationConfig.() -> ModuleConfig.ExternalNotificationConfig): AdminResult<Unit>
suspend fun AdminApi.setStoreForwardConfig(block: ModuleConfig.StoreForwardConfig.() -> ModuleConfig.StoreForwardConfig): AdminResult<Unit>
suspend fun AdminApi.setRangeTestConfig(block: ModuleConfig.RangeTestConfig.() -> ModuleConfig.RangeTestConfig): AdminResult<Unit>
suspend fun AdminApi.setTelemetryConfig(block: ModuleConfig.TelemetryConfig.() -> ModuleConfig.TelemetryConfig): AdminResult<Unit>
suspend fun AdminApi.setCannedMessageConfig(block: ModuleConfig.CannedMessageConfig.() -> ModuleConfig.CannedMessageConfig): AdminResult<Unit>
suspend fun AdminApi.setAudioConfig(block: ModuleConfig.AudioConfig.() -> ModuleConfig.AudioConfig): AdminResult<Unit>
suspend fun AdminApi.setRemoteHardwareConfig(block: ModuleConfig.RemoteHardwareConfig.() -> ModuleConfig.RemoteHardwareConfig): AdminResult<Unit>
suspend fun AdminApi.setNeighborInfoConfig(block: ModuleConfig.NeighborInfoConfig.() -> ModuleConfig.NeighborInfoConfig): AdminResult<Unit>
suspend fun AdminApi.setAmbientLightingConfig(block: ModuleConfig.AmbientLightingConfig.() -> ModuleConfig.AmbientLightingConfig): AdminResult<Unit>
suspend fun AdminApi.setDetectionSensorConfig(block: ModuleConfig.DetectionSensorConfig.() -> ModuleConfig.DetectionSensorConfig): AdminResult<Unit>
suspend fun AdminApi.setPaxcounterConfig(block: ModuleConfig.PaxcounterConfig.() -> ModuleConfig.PaxcounterConfig): AdminResult<Unit>
suspend fun AdminApi.setStatusMessageConfig(block: ModuleConfig.StatusMessageConfig.() -> ModuleConfig.StatusMessageConfig): AdminResult<Unit>
suspend fun AdminApi.setTrafficManagementConfig(block: ModuleConfig.TrafficManagementConfig.() -> ModuleConfig.TrafficManagementConfig): AdminResult<Unit>
```

Usage:
```kotlin
client.admin.setLoraConfig { copy(region = Config.LoRaConfig.RegionCode.US) }
client.admin.setMqttConfig { copy(enabled = true, address = "mqtt.example.com") }

// Combined with editSettings for atomic batching:
client.admin.editSettings {
    setLoraConfig { copy(region = Config.LoRaConfig.RegionCode.US) }
    setMqttConfig { copy(enabled = true) }
}
```

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

    /**
     * Audit + factory-reset detector. On NodeNum mismatch with prior tuple for this identity,
     * implementations MUST atomically `clear()` then persist new tuple. Engine emits
     * `MeshEvent.IdentityRebound(previous, current)`.
     */
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String)

    /** Save the admin session passkey for session resumption. */
    public suspend fun saveSessionPasskey(passkey: SessionPasskey)

    /** Load a previously saved session passkey (null if none persisted). */
    public suspend fun loadSessionPasskey(): SessionPasskey?

    /** Record a heartbeat timestamp for a node (presence tracking). */
    public suspend fun recordHeartbeat(nodeId: NodeId, epochSeconds: Int)

    /** Load the last heartbeat timestamp for a node (null if never seen). */
    public suspend fun loadLastHeartbeat(nodeId: NodeId): Int?

    public suspend fun clear()
}

public data class ConfigBundle(
    public val myInfo: MyNodeInfo,
    public val metadata: DeviceMetadata,
    public val configs: List<Config>,
    public val moduleConfigs: List<ModuleConfig>,
    public val deviceUIConfig: DeviceUIConfig? = null,
)
```

## Transport

```kotlin
public interface RadioTransport {
    public val identity: TransportIdentity
    public suspend fun connect()
    public suspend fun disconnect()
    public suspend fun send(frame: Frame)
    public fun frames(): Flow<Frame>          // cold; one collector
    public val state: StateFlow<TransportState>
}

public sealed interface TransportState {
    public data object Disconnected : TransportState
    public data object Connecting : TransportState
    public data object Bonding : TransportState           // BLE pairing/bonding in progress
    public data object Connected : TransportState
    public data class Error(val cause: Throwable, val recoverable: Boolean) : TransportState
}

public class Frame(public val bytes: kotlinx.io.bytestring.ByteString)
```

## Logging

```kotlin
public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, cause: Throwable?)
    public companion object { public val Silent: LogSink = LogSink { _, _, _, _ -> } }
}
public enum class LogLevel { NONE, VERBOSE, DEBUG, INFO, WARN, ERROR }
```

`LogLevel.NONE` is reserved for the off-state of `Builder.protocolLogging(...)`. The SDK never depends on Kermit / Timber / SLF4J. Hosts wire whatever they want. See [`observability.md`](./observability.md).

## Related

- [ADR-005](./decisions/005-api-shape.md) — design rationale
- [`error-taxonomy.md`](./error-taxonomy.md) — wire→Kotlin error mapping
- [`versioning.md`](./versioning.md) — pre/post-1.0 ABI policy
- [`glossary.md`](./glossary.md) — vocabulary
