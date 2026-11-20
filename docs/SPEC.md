# `meshtastic-sdk` design spec

This spec defines what the SDK is, its module layout, its public API contract, the engine design and the delivery phases. It is a Kotlin Multiplatform SDK that lets an Android, iOS, JVM desktop, Kotlin/JS or Kotlin/Wasm app talk to a Meshtastic node: add a Gradle coordinate, pick a transport and a storage, observe `Flow`s, call `suspend` functions. It carries no host plumbing, DI or UI.

The API dumps in each module's `api/` directory (`api/jvm/<module>.api` and `<module>.klib.api`) are the authoritative signature list. [`api-reference.md`](./api-reference.md) is the browsable guide to them.

## Table of contents

- [0. Scope](#0-scope)
- [1. Project identity](#1-project-identity)
- [2. Module layout](#2-module-layout)
- [3. Public API](#3-public-api)
- [4. Internal architecture](#4-internal-architecture)
- [5. Tooling](#5-tooling)
- [6. Phases](#6-phases)
- [7. Validation](#7-validation)
- [8. Locked defaults](#8-locked-defaults)
- [9. References](#9-references)
- [10. Release protocol](#10-release-protocol)
- [11. What Changed From v1 (for reviewers)](#11-what-changed-from-v1-for-reviewers)

---

## 0. Scope

### What the SDK is

An in-process Kotlin client of the Meshtastic device PhoneAPI. It owns framing, the handshake, the state model, send tracking, admin RPC, telemetry, routing, file transfer and protobuf encoding and decoding. App code consumes it in-process on every target. The wire protocol reference is [`protocol.md`](./protocol.md).

### What the SDK is not

- **Not remote or RPC.** There is no kotlinx-rpc server. Web hosts (`js`, `wasmJs`) run the engine in-process over `transport-websocket` ([ADR-017](./decisions/017-web-targets.md)). A remote-engine adapter for hosts whose node the browser cannot reach stays on the [roadmap](./future/wasm-rpc-roadmap.md).
- **Not a host.** No bound service, foreground-service helper, notification policy, WorkManager or permission flow. Apps wire those.
- **Not a UI library** and not a DI container.
- **Not an MQTT bridge.** MQTT is not a PhoneAPI transport ([`protocol.md`](./protocol.md) §14). The engine surfaces device-as-proxy traffic as `MeshEvent.MqttProxyMessage`; a `transport-mqtt-proxy` side-channel module is on the roadmap.
- **Not a fork of `Meshtastic-Android`.** Code is ported from sibling org repos where useful, never the app's UI, repository or DI stack.

### Hard rules

1. **License: GPL-3.0-or-later** in the repo, README, ADRs and POMs ([ADR-004](./decisions/004-licensing.md)). Downstream adopters take GPL-compatible terms, as with `Meshtastic-Android`.
2. **No `java.*` or `android.*` in `commonMain`.** Public byte payloads are `okio.ByteString`, Wire's runtime type and the single byte vocabulary; `kotlinx-io` is not a dependency. `kotlinx.coroutines.sync.Mutex` is allowed only outside the engine package ([ADR-002](./decisions/002-architecture.md)). Atomics come from atomicfu.
3. **No `kotlin.Result<T>` in any public API.** It does not bridge to Swift.
4. **Public API uses three response shapes:**
   - a throwing `suspend fun` with the sealed `MeshtasticException`, for lifecycle and programmer errors and an unreachable transport;
   - a typed sealed outcome (`SendState`, `AdminResult`, `FileTransferResult`), for expected radio failures such as a NAK, a timeout or a busy device;
   - `Flow` or `StateFlow`, for streams and reactive state.
5. **Single writer to engine state.** `MeshEngine` is an actor: one coroutine drains a `Channel<EngineMessage>`, and all state mutation goes through it.
6. **Pre-handshake bytes are never trusted.** The handshake nonce in `config_complete_id` is the only signal that the session is real. The engine discards everything before it on every transport.
7. **Storage is keyed by `TransportIdentity`** and activated before `transport.connect()`. The identity is stable per transport configuration. The `NodeNum` learned at handshake is recorded in storage but is not the key (see the fragmentation caveat in §3.1 and invariant 4 in §4.3).
8. **One `RadioClient` per radio.** A client owns exactly one transport and one `DeviceStorage` for its lifetime. A host talking to N radios builds N clients, which share nothing. The single-writer engine (rule 5) and per-identity storage (rule 7) both assume one client per radio.
9. **Every public symbol has KDoc.** `explicitApi()` is on for every library module.
10. **Pre-1.0:** breaking changes are allowed with `updateKotlinAbi` and a changelog fragment in [`changelog.d/`](../changelog.d/README.md). **From 1.0:** `checkKotlinAbi` hard-gates the ABI.

### Protocol sources

- **Schema:** [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs), consumed as the published `org.meshtastic:protobufs` artifact pinned in `gradle/libs.versions.toml` ([ADR-015](./decisions/015-consume-published-protobufs-artifact.md)).
- **Behavior reference:** [`meshtastic/firmware`](https://github.com/meshtastic/firmware), read-only.
- **`Meshtastic-Android`:** a port source for the codec, handshake, BLE handling and helpers.
- **Real devices:** the conformance suite (`cli conformance`) runs against them.

---

## 1. Project identity

| | |
|---|---|
| **Repo** | `meshtastic/meshtastic-sdk` |
| **Group ID** | `org.meshtastic`, matching the sibling `org.meshtastic:mqtt-client` |
| **Artifact prefix** | `sdk-` (for example `sdk-core`, `sdk-transport-ble`) |
| **Kotlin package root** | `org.meshtastic.sdk` |
| **License** | GPL-3.0-or-later |
| **Contributor agreement** | DCO (`Signed-off-by:`), no CLA |
| **Targets (per module subset, see §2)** | `androidTarget()`, `jvm()`, `iosArm64()`, `iosX64()`, `iosSimulatorArm64()`, `js()`, `wasmJs()` |
| **Min Android SDK** | 26 |
| **JVM** | JDK 21 toolchain, bytecode and JDK API level 17 (`jvmTarget` in the catalog) |
| **Kotlin, AGP, compile SDK** | pinned in `gradle/libs.versions.toml` |
| **Versioning** | SemVer from git tags via `axion-release-plugin` ([`versioning.md`](./versioning.md)) |

---

## 2. Module layout

```text
meshtastic-sdk/
├── build-logic/convention/   Convention plugins: meshtastic.kmp.library, meshtastic.android.library,
│                             meshtastic.js.library, meshtastic.ios.framework, meshtastic.publishing,
│                             meshtastic.sample.jvm
│
├── core/                     Public facade and engine.            android, jvm, ios, js, wasmJs
│   - RadioClient, Builder, sub-APIs (admin, telemetry, routing, storeForward, files)
│   - sealed ConnectionState, SendState, SendFailure, MeshEvent, NodeChange, MeshtasticException
│   - value-class IDs: NodeId, ChannelIndex, MessageId, TransportIdentity
│   - RadioTransport, Frame, TransportState, TransportSpec, TransportTarget
│   - StorageProvider / DeviceStorage, InMemoryStorageProvider
│   - WireCodec, WireFraming, LogSink, PayloadRedactor, packet and node helpers
│   - MeshEngine (actor), CommandDispatcher, MeshState, XModemMachine (internal)
│   - api(libs.meshtasticProtobufs), so org.meshtastic.proto.* is transitive
│
├── transport-ble/            Kable.                               android, jvm, ios
├── transport-tcp/            Ktor sockets, mDNS discovery.        android, jvm, ios
├── transport-serial/         jSerialComm (jvm, android) and       android, jvm
│                             usb-serial-for-android (android,     (the iOS klib carries only
│                             compileOnly)                          the common types)
├── transport-websocket/      Ktor client WebSockets.              android, jvm, ios, js, wasmJs
│
├── storage-sqldelight/       Persistent storage.                  android, jvm, ios
│
├── testing/                  FakeRadioTransport, TestClock,       android, jvm, ios, js, wasmJs
│                             FromRadio.toFrame()
│
├── bom/                      Maven BOM aligning published artifact versions
│
├── samples/                  Not published
│   ├── cli/                  JVM CLI and conformance harness (ADR-009)
│   ├── parity-app/           Compose Multiplatform app: Android, desktop JVM, iOS (iosApp/ shell)
│   └── parity-android-app/   Android app shell around parity-app
│
└── docs/                     protocol.md, architecture/, decisions/ (ADRs); Dokka publishes to GitHub Pages
```

Every module declares only the targets it compiles for, and CI builds each target. The live dependency graph is [`architecture/module-graph.md`](./architecture/module-graph.md).

**Why multiple modules.** The transports have different platform availability and disjoint heavyweight dependencies (Kable, Ktor, jSerialComm, usb-serial-for-android). One module would push every transport's dependencies onto every consumer and break the per-target subset. The split is for dependency isolation, not code organization ([ADR-006](./decisions/006-multi-module-rationale.md)).

**Deferred modules** (commented out in `settings.gradle.kts`, additive when they land):

- `transport-http`: `TransportSpec.Http` exists, but no HTTP transport ships
- `transport-mqtt-proxy`: `MqttClientProxyMessage` plumbing as a side-channel, not a `RadioTransport`
- `rpc`, `transport-rpc`, `host-rpc-server`, `samples/host-rpc-server`: the remote engine
- `storage-okio-files`, `samples/wasm-app`
- `host-android`, `host-jvm` and DI sugar (`koin`, `kotlin-inject`), only on adopter demand

---

## 3. Public API

The blocks in this section are the contract in outline. Signatures match the API dumps; KDoc lives in source.

### 3.1 `org.meshtastic.sdk` (module `:core`, artifact `org.meshtastic:sdk-core`)

Per [ADR-001](./decisions/001-public-api-uses-generated-protobufs.md), every protocol payload in this API (`MeshPacket`, `NodeInfo`, `Config`, `User`, `Channel`, `Position`, `Telemetry`, `AdminMessage`, `PortNum` and the rest) is the Wire-generated type from `org.meshtastic.proto.*`. The SDK declares only what the schema lacks: lifecycle, transport, IDs and send tracking.

```kotlin
public class RadioClient internal constructor(/* ... */) {

    public val connection: StateFlow<ConnectionState>

    /** The local node, available after handshake. */
    public val ownNode: StateFlow<NodeInfo?>

    /** Most recently committed ConfigBundle for the active session; null until Connected. */
    public val configBundle: StateFlow<ConfigBundle?>

    /** Channel list for the active session; null until Connected. */
    public val channels: StateFlow<List<Channel>?>

    /** Per-node deltas. Every subscriber receives a Snapshot first, then live changes. */
    public val nodes: Flow<NodeChange>

    /**
     * Decoded inbound mesh packets; `MeshPacket.decoded` is always non-null. The device decrypts
     * channel traffic; a packet that arrives without a decoded payload is dropped (§4.3, invariant 9).
     */
    public val packets: Flow<MeshPacket>

    /** Side-channel events: errors, queue status, notifications, drops, identity changes, congestion, MQTT. */
    public val events: Flow<MeshEvent>

    /** Every FromRadio envelope, unfiltered. Hot, no replay, drops the oldest past 256 behind. Not redacted. */
    public val fromRadio: Flow<FromRadio>

    /** Admin session state per node that issued a passkey; an absent node has NoSession. */
    public val adminSessions: StateFlow<Map<NodeId, AdminSessionStatus>>
    public fun adminSessionStatus(node: NodeId): Flow<AdminSessionStatus>

    public suspend fun nodeSnapshot(): Map<NodeId, NodeInfo>
    public fun requestNodeInfo(node: NodeId): MessageHandle

    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun connect()

    /** Idempotent; never throws. */
    public suspend fun disconnect()

    /**
     * Enqueue an outbound packet and return a handle whose state tracks
     * Queued -> Sent -> Acked/Delivered/Failed. The engine fills in `id`, `from` and the channel
     * hash; a caller may set them. Throws only on programmer error (not connected, payload too large).
     */
    @Throws(MeshtasticException::class)
    public fun send(packet: MeshPacket): MessageHandle

    public fun sendText(text: String, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), replyId: Int = 0): MessageHandle
    public fun sendReaction(emoji: String, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), replyId: Int): MessageHandle
    public suspend fun send(portnum: PortNum, payload: ByteArray, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), wantAck: Boolean = false, hopLimit: Int? = null): MessageHandle
    public suspend fun send(portnum: PortNum, payload: okio.ByteString, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), wantAck: Boolean = false, hopLimit: Int? = null): MessageHandle

    /** Low-level escape hatch: write a raw ToRadio. */
    public fun sendRaw(frame: ToRadio)

    public val admin: AdminApi
    public val telemetry: TelemetryApi
    public val routing: RoutingApi
    public val storeForward: StoreForwardApi
    public val files: FileTransferApi

    public companion object { public fun Builder(): Builder }

    public class Builder {
        public fun transport(transport: RadioTransport): Builder        // required
        public fun storage(provider: StorageProvider): Builder          // required, no default
        public fun logger(sink: LogSink): Builder                       // default: LogSink.Silent
        public fun clock(clock: kotlin.time.Clock): Builder             // default: Clock.System
        public fun coroutineContext(ctx: CoroutineContext): Builder
        public fun autoSyncTimeOnConnect(enabled: Boolean): Builder     // default: true
        public fun disableBleHeartbeat(): Builder                       // default: heartbeat on BLE too
        public fun protocolLogging(level: LogLevel, redactor: PayloadRedactor = PayloadRedactor.Default): Builder
        public fun sendTimeout(duration: Duration): Builder             // default: 30 s per-send ACK timeout
        public fun rpcTimeout(duration: Duration): Builder              // default: 30 s per admin RPC
        public fun presenceTimeout(timeout: Duration): Builder          // default: 2 h online window
        public fun autoReconnect(                                       // default: disabled; 1.0 flips it on
            enabled: Boolean = true,
            initialBackoff: Duration = 1.seconds,
            maxBackoff: Duration = 60.seconds,
            maxAttempts: Int? = null,
            backoffMultiplier: Double = 2.0,
            jitter: Double = 0.2,
            recoveryStep: ReconnectRecoveryStep? = null,
        ): Builder
        public fun autoReconnect(config: AutoReconnectConfig): Builder
        public fun skipNodeDb(skip: Boolean = true): Builder             // default: false; stop after Stage 1
        public fun build(): RadioClient
    }
}

/** DSL form of the builder. */
public fun RadioClient(block: RadioClient.Builder.() -> Unit): RadioClient

/** Connect and suspend until the handshake settles; returns the ConfigBundle. */
public suspend fun RadioClient.connectAndAwaitReady(timeout: Duration = 30.seconds): ConfigBundle

/**
 * Connect, run the block and always disconnect, on success, failure or cancellation; teardown is
 * bounded by the timeout under NonCancellable. Lifecycle is suspend-only: there is no
 * AutoCloseable or blocking close(), which would invite an ANR on Android or a deadlock on iOS.
 */
public suspend fun <T> RadioClient.withConnection(teardownTimeout: Duration = 10.seconds, block: suspend RadioClient.() -> T): T

/** DSL form of send: `client.send { text("hello"); to(node); wantAck() }`. */
public suspend fun RadioClient.send(block: SendBuilder.() -> Unit): MessageHandle
```

`core` also ships helpers outside the class: payload accessors on `MeshPacket` for every port (`asText()`, `asPosition()`, `asTelemetry()` and the rest), packet provenance (relay node, next hop, signature, ACK proof), `Flow<MeshPacket>.dropDuplicateStoreForwardReplays()`, typed config editors on `AdminApi` (`setLoraConfig { }` and the rest), `RadioClient.exportDeviceProfile()` / `installDeviceProfile()`, `RadioClient.enableHamMode()`, LoRa region and frequency-slot math (`LoRaRegionInfo`, `LoRaFrequencyPlan`), channel and contact URLs, and `MessageHandle.retry()` / `retryWith(RetryPolicy)`. [`api-reference.md`](./api-reference.md) lists them.

**`AutoReconnectConfig`** configures the engine's reconnect supervisor:

```kotlin
public data class AutoReconnectConfig(
    val enabled: Boolean = true,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 60.seconds,
    val maxAttempts: Int? = null,               // null = retry indefinitely
    val backoffMultiplier: Double = 2.0,
    val jitter: Double = 0.2,                   // ±20% symmetric randomization
    val recoveryStep: ReconnectRecoveryStep? = null,
) {
    public companion object {
        public val Disabled: AutoReconnectConfig = AutoReconnectConfig(enabled = false)
    }
}

/** Runs after the backoff delay and before transport.connect(); throwing counts as a failed attempt. */
public fun interface ReconnectRecoveryStep {
    public suspend fun recover(attempt: Int, cause: MeshtasticException)
}
```

Backoff: `delay(n) = min(initialBackoff * backoffMultiplier^(n-1), maxBackoff) * (1 ± jitter * random())`, on `kotlinx.coroutines.delay` so `runTest` virtual time applies.

```kotlin
public class MessageHandle internal constructor(
    public val id: MessageId,
    public val state: StateFlow<SendState>,
) {
    /**
     * Suspends until Acked, Delivered or Failed. A disconnect before that resolves the state to
     * Failed(Disconnected). Cancelling the caller rethrows CancellationException and leaves the
     * handle tracking; use cancel() to withdraw the send.
     */
    public suspend fun await(): SendOutcome

    /** Best-effort and idempotent. Queued: removed, state becomes Failed(Cancelled). Sent or later: no effect. */
    public fun cancel()
}

public sealed interface SendOutcome {
    public data object Success : SendOutcome
    public data class Failure(val reason: SendFailure) : SendOutcome
}

public sealed interface SendState {
    public data object Queued : SendState
    public data object Sent : SendState                                // device accepted
    public data object Acked : SendState                               // unicast routing ACK heard
    public data object Delivered : SendState                           // broadcast: rebroadcast heard
    public data class Failed(val reason: SendFailure) : SendState
}

public sealed interface SendFailure {
    public data object NoRoute : SendFailure                           // Routing.Error.NO_ROUTE
    public data object MaxRetransmit : SendFailure                     // Routing.Error.MAX_RETRANSMIT
    public data object Timeout : SendFailure
    public data object DutyCycleLimit : SendFailure                    // Routing.Error.DUTY_CYCLE_LIMIT
    public data object RateLimited : SendFailure                       // Routing.Error.RATE_LIMIT_EXCEEDED; nothing sent
    public data object Disconnected : SendFailure                      // transport dropped mid-send
    public data object HandshakeFailed : SendFailure                   // session never reached Ready
    public data object Cancelled : SendFailure                         // MessageHandle.cancel() before Sent
    public data object IdCollision : SendFailure                       // id matches an in-flight send
    public data object AckTimeout : SendFailure                        // no ACK within sendTimeout (unicast want_ack)
    public data class QueueRejected(val res: Int) : SendFailure        // QueueStatus.res rejected the packet
    public data class Other(val routingError: Routing.Error) : SendFailure
    public data class Unknown(val message: String) : SendFailure
}
```

`QueueStatus.res` is in the firmware's ERRNO namespace: `0` and `35` (`ERRNO_SHOULD_RELEASE`) mean success, `32` to `34` are enqueue rejections (queue full, no interface, radio disabled), and `1` to `31` are `Routing.Error` codes. A payload over `DATA_PAYLOAD_LEN` (233 bytes) is rejected before enqueue: `send()` throws `MeshtasticException.PayloadTooLarge` and returns no handle, so it is never a `SendFailure`.

```kotlin
public sealed interface MeshEvent {
    public data class QueueStatusChanged(val status: QueueStatus) : MeshEvent
    public data class Notification(val notification: ClientNotification) : MeshEvent
    public data class CongestionWarning(val metrics: CongestionMetrics) : MeshEvent
    public data object MqttConnected : MeshEvent
    public data class MqttDisconnected(val reason: String? = null) : MeshEvent
    /** Device-as-MQTT-proxy traffic (FromRadio.mqttClientProxyMessage). */
    public data class MqttProxyMessage(val message: MqttClientProxyMessage) : MeshEvent
    public data class XmodemPacket(val packet: XModem) : MeshEvent
    /** Named FileInfoReceived to avoid clashing with the proto FileInfo. */
    public data class FileInfoReceived(val info: org.meshtastic.proto.FileInfo) : MeshEvent
    /** Lockdown state from a MESHTASTIC_LOCKDOWN build. */
    public data class LockdownStatusChanged(val status: LockdownStatus) : MeshEvent
    /** The failure that ended the session, as its own MeshtasticException subtype. */
    public data class TransportError(val error: MeshtasticException) : MeshEvent
    public data class ProtocolWarning(val message: String, val details: Map<String, Any?> = emptyMap()) : MeshEvent
    /**
     * The device reported a NodeNum different from the stored one. Emitted before storage changes.
     * reason is "identity_mismatch" (storage cleared) or "node_renumbered" (same device_id, storage kept).
     */
    public data class IdentityRebound(val previousNodeNum: NodeId, val newNodeNum: NodeId, val reason: String = "device factory reset or identity change") : MeshEvent
    /** A storage write failed; the session continues in memory. At most once per connect. */
    public data class StorageDegraded(val reason: String) : MeshEvent
    /** Declared; the engine does not emit it (docs/roadmap.md R-17). */
    public data class KeyVerification(val prompt: KeyVerificationPrompt) : MeshEvent
    public sealed interface SecurityWarning : MeshEvent {
        public data object DuplicatedPublicKey : SecurityWarning
        public data object LowEntropyKey : SecurityWarning
    }
    /** A public flow dropped `count` items (§4.4). */
    public data class PacketsDropped(val flow: DroppedFlow, val count: Int) : MeshEvent
    /** FromRadio.rebooted = true; session state is stale. */
    public data class DeviceRebooted(val reason: String = "device reported reboot") : MeshEvent
    /** Another client changed channels or config on this device. */
    public data class ExternalConfigChange(val kind: ExternalChangeKind) : MeshEvent
}

public enum class DroppedFlow { Packets, Events }
public enum class ExternalChangeKind { CHANNEL, CONFIG, MODULE_CONFIG }

public sealed interface NodeChange {
    /** First emission to every subscriber, built from the engine's node map via onSubscription. */
    public data class Snapshot(val nodes: Map<NodeId, NodeInfo>) : NodeChange
    public data class Added(val node: NodeInfo) : NodeChange
    public data class Updated(val node: NodeInfo, val changed: Set<NodeField>) : NodeChange
    public data class Removed(val nodeId: NodeId) : NodeChange
    /** Not heard within presenceTimeout. */
    public data class WentOffline(val nodeId: NodeId, val lastHeardSec: Int) : NodeChange
    public data class CameOnline(val nodeId: NodeId) : NodeChange
}

public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data class Connecting(val attempt: Int) : ConnectionState
    public data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState
    public data object Connected : ConnectionState
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int) : ConnectionState
}
// No DeviceSleep state: devices do not announce sleep on the wire. Config.power.ls_secs gives the timing.

public val ConnectionState.isUsable: Boolean          // Connected only
public val ConnectionState.isInProgress: Boolean      // Connecting, Configuring, Reconnecting
public val ConnectionState.statusMessage: String

public sealed interface AdminSessionStatus {
    public data object NoSession : AdminSessionStatus
    public data class Active(val refreshedAt: Instant, val staleAt: Instant) : AdminSessionStatus   // refreshedAt + 150 s
    public data class Stale(val refreshedAt: Instant, val expiresAt: Instant) : AdminSessionStatus  // refreshedAt + 300 s
}

public sealed interface TransportSpec {
    public val identity: TransportIdentity
    public data class Ble(val address: String) : TransportSpec
    public data class Tcp(val host: String, val port: Int = 4403) : TransportSpec
    public data class Http(val baseUrl: String) : TransportSpec        // no HTTP transport ships
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

/** A transport named by a compact string: `ble:NEEDLE`, `tcp:HOST[:PORT]`, `serial:PORT[:BAUD]`. */
public sealed interface TransportTarget {
    public val label: String
    public data class Ble(val needle: String, val scanSeconds: Int = DEFAULT_BLE_SCAN_SECONDS) : TransportTarget
    public data class Tcp(val host: String, val port: Int = DEFAULT_TCP_PORT) : TransportTarget
    public data class Serial(val port: String, val baud: Int = DEFAULT_SERIAL_BAUD) : TransportTarget
    public companion object {
        public fun parse(spec: String): TransportTarget?   // null for an unknown scheme or a malformed value
    }
}
```

`TransportTarget.parse` reads a spec; opening the matching transport is host code, because only the host knows which transport modules it links. `WebSocketTransport` derives its own identity, `ws:<scheme>://<host>:<port><path>`, and has no `TransportSpec` case.

**Identity fragmentation.** An identity echoes the literal host the consumer supplies, so `meshtastic.local` and `192.168.1.42` give the same node two identities and two stores. The SDK does not canonicalize DNS, which is platform-dependent and could change the key on every connect. A consumer that wants one store resolves the host once and reuses the address. Invariant 4 in §4.3 bounds the risk when an identity starts pointing at a different node.

```kotlin
public sealed class MeshtasticException(message: String, cause: Throwable?) : Exception(message, cause) {
    public var transportIdentity: TransportIdentity?   // diagnostic context
    public var operation: String?                      // for example "connect", "engine.disconnect"

    public class Transport(reason: String, cause: Throwable? = null) : MeshtasticException
    public class Protocol(reason: String, cause: Throwable? = null) : MeshtasticException
    public class HandshakeTimeout(public val stage: String) : MeshtasticException
    public class StorageUnavailable(message: String = "Storage unavailable", cause: Throwable? = null) : MeshtasticException
    public class FirmwareTooOld(public val required: Int, public val present: Int) : MeshtasticException   // declared, never thrown
    public class NotConnected : MeshtasticException
    public class AlreadyConnected : MeshtasticException
    public class PayloadTooLarge(public val maxBytes: Int) : MeshtasticException

    public companion object {
        public fun <T : MeshtasticException> tag(error: T, transportIdentity: TransportIdentity? = null, operation: String? = null): T
    }
}

@JvmInline public value class NodeId(public val raw: Int) {
    public companion object {
        public val LOCAL: NodeId = NodeId(0)
        public val BROADCAST: NodeId = NodeId(0xFFFFFFFF.toInt())
    }
}
@JvmInline public value class ChannelIndex(public val raw: Int)
@JvmInline public value class MessageId(public val raw: Int)
```

**Why three response shapes.** Throwing for transport and programmer errors keeps `try { client.connect() }` natural. NAKs and timeouts are routine on a mesh, so `SendState`, `AdminResult` and `FileTransferResult` carry them without unwinding the stack. Streams are `Flow`. [`error-taxonomy.md`](./error-taxonomy.md) maps every failure to its shape.

**Why `NodeChange` instead of `StateFlow<Map<NodeId, NodeInfo>>`.** A 200-node mesh with frequent telemetry would emit a 200-entry map on every update. Deltas are O(1), and a subscriber can fold them into a `StateFlow` (`nodeMap()`, `asNodeMap()`). The first emission is always a `Snapshot`, so a late subscriber starts complete.

**Why `Flow<MeshPacket>` instead of a sealed message hierarchy.** The protobuf schema is the domain model (ADR-001). Apps switch on `packet.decoded.portnum` and decode the payload, or use the payload accessors; there is no parallel hierarchy to lag the firmware.

### 3.2 Sub-API namespaces

Types named without a package prefix are Wire-generated types from `org.meshtastic.proto.*`.

```kotlin
public interface AdminApi {
    /** An AdminApi that targets a remote node. Its edits never touch the local radio cache. */
    public fun forNode(dest: NodeId): AdminApi

    // Device info
    public suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata>
    public suspend fun getDeviceConnectionStatus(): AdminResult<DeviceConnectionStatus>
    public suspend fun getRemoteHardwarePins(): AdminResult<NodeRemoteHardwarePinsResponse>

    // Config
    public suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config>
    public suspend fun setConfig(config: Config): AdminResult<Unit>
    public suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig>
    public suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit>

    // Owner and channels
    public suspend fun getOwner(): AdminResult<User>
    public suspend fun setOwner(user: User): AdminResult<Unit>
    public suspend fun getChannel(index: ChannelIndex): AdminResult<Channel>
    public suspend fun setChannel(channel: Channel): AdminResult<Unit>
    public suspend fun listChannels(): AdminResult<List<Channel>>

    // Node management
    public suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit>
    public suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit>
    public suspend fun toggleMuted(node: NodeId): AdminResult<Unit>
    public suspend fun removeNode(node: NodeId): AdminResult<Unit>

    // Position
    public suspend fun setFixedPosition(position: Position): AdminResult<Unit>
    public suspend fun removeFixedPosition(): AdminResult<Unit>

    // Device UI, canned messages, ringtone, ham
    public suspend fun getUIConfig(): AdminResult<DeviceUIConfig>
    public suspend fun storeUIConfig(config: DeviceUIConfig): AdminResult<Unit>
    public suspend fun getCannedMessages(): AdminResult<String>
    public suspend fun setCannedMessages(messages: String): AdminResult<Unit>
    public suspend fun getRingtone(): AdminResult<String>
    public suspend fun setRingtone(rtttl: String): AdminResult<Unit>
    public suspend fun setHamMode(params: HamParameters): AdminResult<Unit>

    // DFU and files
    public suspend fun enterDfuMode(): AdminResult<Unit>
    public suspend fun deleteFile(path: String): AdminResult<Unit>

    // Backup and restore
    public suspend fun backupPreferences(location: AdminMessage.BackupLocation = FLASH): AdminResult<Unit>
    public suspend fun restorePreferences(location: AdminMessage.BackupLocation = FLASH): AdminResult<Unit>
    public suspend fun removeBackupPreferences(location: AdminMessage.BackupLocation = FLASH): AdminResult<Unit>

    // Contacts and key verification
    public suspend fun addContact(contact: SharedContact): AdminResult<Unit>
    public suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit>

    // Lockdown (MESHTASTIC_LOCKDOWN builds; local only, fire-and-forget)
    public suspend fun lockdown(auth: LockdownAuth): AdminResult<Unit>

    // OTA, sensor, simulator, display
    public suspend fun rebootOta(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun otaRequest(event: AdminMessage.OTAEvent): AdminResult<Unit>
    public suspend fun setSensorConfig(config: SensorConfig): AdminResult<Unit>
    public suspend fun exitSimulator(): AdminResult<Unit>
    public suspend fun setScale(scale: Int): AdminResult<Unit>
    public suspend fun sendInputEvent(event: AdminMessage.InputEvent): AdminResult<Unit>

    // Lifecycle
    public suspend fun reboot(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun shutdown(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun factoryReset(preserveBleBonds: Boolean = true): AdminResult<Unit>
    public suspend fun nodeDbReset(): AdminResult<Unit>

    // Time
    public suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit>
    public suspend fun setTime(at: Instant? = null): AdminResult<Unit>

    // Transactional batched writes; the transaction closes when the block throws or is cancelled
    public suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T>
    public suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T
}

public interface AdminEdit {
    public suspend fun setConfig(config: Config)
    public suspend fun setModuleConfig(config: ModuleConfig)
    public suspend fun setOwner(user: User)
    public suspend fun setChannel(channel: Channel)
    public suspend fun setFavorite(node: NodeId, favorite: Boolean)
    public suspend fun setIgnored(node: NodeId, ignored: Boolean)
}

public interface AdminBatchScope : AdminEdit {
    public suspend fun getConfig(type: AdminMessage.ConfigType): Config
    public suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): ModuleConfig
    public suspend fun listChannels(): List<Channel>
}

public sealed interface AdminResult<out T> {
    public data class Success<T>(val value: T) : AdminResult<T>
    public data object SessionKeyExpired : AdminResult<Nothing>
    public data object Unauthorized : AdminResult<Nothing>
    public data object Timeout : AdminResult<Nothing>
    public data object RateLimited : AdminResult<Nothing>           // Routing.Error.RATE_LIMIT_EXCEEDED
    public data object NodeUnreachable : AdminResult<Nothing>
    public data class Failed(val routingError: Routing.Error) : AdminResult<Nothing>
}

public fun <T> AdminResult<T>.getOrNull(): T?
public fun <T> AdminResult<T>.getOrElse(default: T): T
public inline fun <T> AdminResult<T>.getOrElse(block: (AdminResult<T>) -> T): T
public val <T> AdminResult<T>.isSuccess: Boolean
public inline fun <T, R> AdminResult<T>.map(transform: (T) -> R): AdminResult<R>
public inline fun <T, R> AdminResult<T>.fold(onSuccess: (T) -> R, onFailure: (AdminResult<T>) -> R): R
public inline fun <T> AdminResult<T>.onSuccess(action: (T) -> Unit): AdminResult<T>
public inline fun <T> AdminResult<T>.onFailure(action: (AdminResult<T>) -> Unit): AdminResult<T>
public fun <T> AdminResult<T>.getOrThrow(): T   // throws AdminResultException on failure

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

public interface RoutingApi {
    public suspend fun traceRoute(dest: NodeId, hopLimit: Int = DEFAULT_HOP_LIMIT): AdminResult<RouteDiscovery>
    public suspend fun requestNeighborInfo(node: NodeId = NodeId.LOCAL): AdminResult<NeighborInfo>
}

public interface StoreForwardApi {
    /** Known store-and-forward servers on the mesh. */
    public val servers: StateFlow<List<NodeId>>
    /** Replays arrive on RadioClient.packets; returns the count the server reports pending. */
    public suspend fun requestHistory(since: Int? = null, server: NodeId? = null): AdminResult<Int>
    public suspend fun requestStats(server: NodeId? = null): AdminResult<StoreForwardStats>
    public val events: Flow<StoreForwardEvent>
}

/** XModem file transfer. One transfer at a time; files up to MAX_FILE_BYTES (1 MiB), paths up to MAX_PATH_BYTES (127). */
public interface FileTransferApi {
    public val progress: StateFlow<FileTransferProgress?>
    public suspend fun download(path: String): FileTransferResult<okio.ByteString>
    public suspend fun upload(path: String, data: okio.ByteString): FileTransferResult<Unit>
}

public sealed interface FileTransferResult<out T> {
    public data class Success<T>(val value: T) : FileTransferResult<T>
    public data class Failure(val reason: FileTransferFailure) : FileTransferResult<Nothing>
}

public sealed interface FileTransferFailure {
    // NotConnected, Busy, InvalidPath, TooLarge, Refused, DeviceCancelled, RetriesExhausted, Timeout
}
```

Firmware update (flashing) is not part of the SDK.

### 3.3 Storage contract

```kotlin
public interface StorageProvider {
    /** Called before transport.connect(). */
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

    /** Records the NodeNum for this identity. A different NodeNum MUST atomically clear() before persisting. */
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String)

    /**
     * As above, with the validated MyNodeInfo.device_id. A changed NodeNum with the same stored
     * device id is a renumber: remove only the old own-node row instead of clearing. The default
     * ignores deviceId and delegates to the two-argument overload.
     */
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String, deviceId: okio.ByteString?)
    public suspend fun loadOwnDeviceId(): okio.ByteString?   // default: null

    public suspend fun clear()
    override fun close()

    /** Host-facing only: the engine keeps admin passkeys in memory and never calls these. */
    public suspend fun saveSessionPasskey(passkey: SessionPasskey)
    public suspend fun loadSessionPasskey(): SessionPasskey?

    /** Per-node heard timestamps, so presence survives process death. */
    public suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long)
    public suspend fun loadHeartbeats(): Map<NodeId, Long>
}

/** Everything received during the configure handshake. */
public data class ConfigBundle(
    public val myInfo: MyNodeInfo,
    public val metadata: DeviceMetadata,
    public val configs: List<Config>,
    public val moduleConfigs: List<ModuleConfig>,
    public val deviceUIConfig: DeviceUIConfig? = null,
)

public data class SessionPasskey(public val bytes: okio.ByteString, public val expiresAtEpochMs: Long)
```

`Builder.storage(...)` is required and has no default. `:core` ships `InMemoryStorageProvider`, RAM only, for a host that wants no persistence. `:storage-sqldelight` is the persistent backend ([`architecture/storage.md`](./architecture/storage.md)), or a host writes its own.

### 3.4 Transport contract

```kotlin
public interface RadioTransport {
    public val identity: TransportIdentity
    public suspend fun connect()
    public suspend fun disconnect()
    public suspend fun send(frame: Frame)
    public fun frames(): Flow<Frame>                                 // cold; one collector
    public val state: StateFlow<TransportState>                      // the engine observes it
}

public sealed interface TransportState {
    public data object Disconnected : TransportState
    public data object Connecting : TransportState
    /**
     * The link is up and the platform is negotiating pairing (notably BLE). connect() is still
     * suspended and the handshake clock has not started, so a host can show a pairing prompt.
     * Flow: Connecting -> Bonding -> Connected.
     */
    public data object Bonding : TransportState
    public data object Connected : TransportState
    public data class Error(val cause: Throwable, val recoverable: Boolean) : TransportState
}

public data class Frame(public val bytes: okio.ByteString)
```

Shipped transports and their discovery:

| Module | Open a transport | Discovery | Notes |
|---|---|---|---|
| `transport-tcp` | `TcpTransport(host, port = 4403)` | `TcpDiscovery.discover(): Flow<TcpDiscoveryEvent>` over mDNS `_meshtastic._tcp` (`JvmTcpDiscovery`, `AndroidTcpDiscovery(context)`, `IosTcpDiscovery`); `DiscoveredTcpRadio.toTransport()` | |
| `transport-ble` | `BleTransport(radio: DiscoveredRadio, ...)`; on Android also `BleTransport(device: BondedBleDevice, ...)` and `BleTransport(address, ...)` | `BleScanner.scan(): Flow<DiscoveredRadio>`; `bondedBleDevices(context)` on Android | Opt-in `BleResilience`; `deviceLogs(): Flow<LogRecord>` reads the `logradio` characteristic |
| `transport-serial` | `JvmSerialPorts.open(portName, baudRate)`, `AndroidSerialPorts.open(...)`, `AndroidUsbSerialPorts.open(...)` | `JvmSerialPorts.list()` and `findMeshtastic()`, `AndroidSerialPorts.list()`, `AndroidUsbSerialPorts.list(context)`, `SerialPortInfo.isLikelyMeshtastic()` | An `onTextLine` overload receives the text the device prints between frames |
| `transport-websocket` | `WebSocketTransport(url, httpClient = null)` for `ws://` and `wss://` (`/api/v1/ws`) | none | Needs unmerged firmware support; see its [`Module.md`](../transport-websocket/Module.md) |

[`architecture/transport-comparison.md`](./architecture/transport-comparison.md) compares their behavior, and [`architecture/transport-isolation.md`](./architecture/transport-isolation.md) covers how they stay out of `:core`.

### 3.5 Send DSL

```kotlin
@DslMarker public annotation class MeshSendDsl

@MeshSendDsl
public class SendBuilder internal constructor() {
    public fun to(nodeId: NodeId)
    public fun channel(channel: ChannelIndex)
    public fun wantAck()
    public fun wantAck(value: Boolean)
    public fun hopLimit(hops: Int?)                            // null: the device's lora.hop_limit
    public fun text(text: String)                              // TEXT_MESSAGE_APP payload
    public fun data(portnum: PortNum, bytes: ByteArray)        // any portnum, raw bytes
    public fun position(latLng: LatLng)                        // POSITION_APP payload
    public fun proto(packet: MeshPacket)                       // use the packet verbatim
}

// Call exactly one of text, data, position or proto per builder.
// After proto(), the setters (to, channel, wantAck, hopLimit) throw IllegalStateException.
```

### 3.6 Logging

```kotlin
public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, cause: Throwable?)
    public companion object { public val Silent: LogSink }
}
```

The SDK exposes no logging framework ([ADR-011](./decisions/011-logsink.md)); apps route `LogSink` into their own. Envelope-level logging is a separate opt-in, `Builder.protocolLogging(level, redactor)`, which logs every `FromRadio` and `ToRadio` under the tag `Protocol`. `PayloadRedactor.Default` prints metadata and never payload content.

---

## 4. Internal architecture

### 4.1 The actor

```text
                ┌────────────────────────────────────────────────────┐
                │                  MeshEngine                        │
                │  one coroutine, drains Channel<EngineMessage>      │
                │  ─ owns: MeshState, CommandDispatcher,             │
                │           pendingSends map, handshake FSM,         │
                │           presence tracker, reconnect supervisor   │
                └───▲─────────────▲──────────────────────────▲───────┘
                    │             │                          │
          EngineMessage.    EngineMessage.FrameRx      EngineMessage.Send/
          TransportState         │                     PostRpc/Timer/...
          Changed                │                          │
                    │     ┌──────┴──────┐         ┌─────────┴────────┐
             ┌──────┴──┐  │ FrameReader │         │  Public API call │
             │Transport│  │ (collects   │         │  (suspends until │
             │Observer │  │  transport. │         │  acked back via  │
             │(state   │  │  frames())  │         │  CompletableD.)  │
             │ flow)   │  └─────────────┘         └──────────────────┘
             └─────────┘

Outbound writes:  Engine → Channel<Frame> → OutboundWriter → transport.send(frame) (sequential; FIFO)
Outbound state:   Engine → MutableStateFlow / MutableSharedFlow → public Flows
```

All state mutation happens on the engine coroutine, with no `Mutex` and no atomics on state. [`architecture/engine-actor.md`](./architecture/engine-actor.md) has the detail.

### 4.2 Components

The engine is one class, `MeshEngine`, plus a few collaborators. A component exists to own a responsibility, not to be mockable.

| Component | Responsibility |
|---|---|
| `WireCodec` | Pure encode and decode of `ToRadio` ↔ framed bytes ↔ `FromRadio`, with resync. `FrameDecoder` decodes a TCP or serial stream byte by byte. No IO. |
| `WireFraming` | The framing constants: sync bytes `0x94 0xC3`, `HEADER_SIZE`, `MAX_PAYLOAD_SIZE`, `MAX_FRAME_ON_WIRE`. Shared by the stream transports. |
| Handshake FSM | Stage 1 → 100 ms settle → heartbeat → 100 ms settle → Stage 2 → seed the local admin passkey with `get_owner_request`. Implemented in `MeshEngine` on the `HandshakeStage` enum. Stage 1 retries once at half its budget; Stage 2 uses a sliding watchdog with a hard cap. `Builder.skipNodeDb()` stops after Stage 1. [`architecture/handshake-fsm.md`](./architecture/handshake-fsm.md). |
| `MeshState` | Immutable snapshot: node map, own node info, channels, `ConfigBundle`, version counter. Replaced on mutation. |
| `CommandDispatcher` | Registers `(requestId, ResponseKind, CompletableDeferred)` entries, completes or times them out on matching inbound packets, and classifies routing errors for RPCs. |
| Send tracking | `pendingSends: Map<MessageId, MutableStateFlow<SendState>>`, updated from `QueueStatus` (Queued → Sent) and `Routing` ACK or NAK. Per-send ACK timeout jobs. Broadcasts settle without an ACK. The SDK never retries mesh delivery. |
| Live node merge | Every decoded packet creates or refreshes its sender (`last_heard`, `snr`, `hops_away`, `via_mqtt`); `NODEINFO_APP` merges `user`, `POSITION_APP` merges `position`, and `TELEMETRY_APP` device metrics merge too, each emitting one `NodeChange`. |
| Presence tracker | Per-node last-heard map and offline set. A periodic tick emits `NodeChange.WentOffline` and `CameOnline` against `presenceTimeout`. Timestamps persist through `DeviceStorage`. |
| Reconnect supervisor | Exponential-backoff reconnect on recoverable transport errors, configured by `AutoReconnectConfig` (off by default before 1.0). Runs the optional `ReconnectRecoveryStep`, resets session state and re-enters Stage 1. |
| Liveness watchdog | A 60 s budget (twice the heartbeat interval), reset by every decoded `FromRadio`. Exhaustion emits `TransportError` and tears down. Catches half-open sockets and NAT timeouts. |
| Duplicate detection | Bounded set of `(from, to, channel, id)` keys, 256 entries, oldest evicted first. |
| Admin sessions | Per-node passkeys held in memory only, latched from response-shaped admin messages and attached to remote admin packets. A key is `Active` for 150 s after receipt, `Stale` until 300 s, then dropped; `ADMIN_BAD_SESSION_KEY` clears it and triggers one re-authentication. Published on `RadioClient.adminSessions`. |
| Local config cache | Admin writes to the local node update `configBundle` and `channels`; writes through `forNode(...)` do not. Unsolicited admin messages from the local device (`request_id == 0`) update the cache and emit `MeshEvent.ExternalConfigChange`. |
| File transfer | `XModemMachine` drives XModem for `RadioClient.files`, one transfer at a time. |
| Raw tap | Every decoded `FromRadio` also goes to `fromRadio`: 256-entry buffer, oldest dropped. |
| Protocol logging | Logs each envelope through `PayloadRedactor` when `Builder.protocolLogging` is set. |
| Congestion monitoring | Watches channel utilization and `air_util_tx` in device metrics and emits `MeshEvent.CongestionWarning` when the level changes. |

### 4.3 Anchor invariants

These are the protocol behaviors any working Meshtastic client must honor. They gate every engine change. The engine tests under `core/src/commonTest/kotlin/org/meshtastic/sdk/` cover them (for example `EngineTest`, `HandshakeFsmTest`, `IdentityRebindTest`, `StorageResilienceTest`).

1. **FIFO frame order.** One writer feeds the transport, so outbound frames never reorder under concurrent sends.
2. **Transport-up is not session-ready.** `ConnectionState.Connected` is reached only after `config_complete_id` matches the session nonce. Until then the state is `Connecting` or `Configuring`.
3. **Pre-handshake bytes are discarded.** A `FromRadio` received between transport connect and the matching `config_complete_id` for the current nonce never reaches the public surface. This holds on every transport.
4. **Storage is activated before `transport.connect()`,** keyed by `TransportIdentity`, and never re-keyed. If `recordOwnNode` reports a `NodeNum` different from the stored one for the same identity (factory reset, device swap, a host name pointing elsewhere), `DeviceStorage` MUST `clear()` before persisting the new tuple, so stale rows from the previous node cannot leak in. The exception is a renumber: the same validated `device_id` under a new `NodeNum` (firmware 2.8 derives the `NodeNum` from the public key) keeps the store and removes only the old own-node row.
5. **The handshake is an explicit FSM.**
6. **Admin requests are `request_id`-correlated** with a timeout (`rpcTimeout`, default 30 s). Nothing waits forever.
7. **Mesh delivery retries belong to the device.** The SDK never re-enqueues a packet after `Sent` on its own timer; it waits for the device's `Routing` outcome.
8. **Heartbeat.** The engine sends `ToRadio(heartbeat = Heartbeat(nonce = n))` every 30 s. It is mandatory on TCP and serial; on BLE it is on by default and `Builder.disableBleHeartbeat()` turns it off. Nonces count up from 2 and never take 0 or 1: firmware drops a `ToRadio` write byte-identical to the previous one, and reads `nonce == 1` as "broadcast our nodeinfo over LoRa". See [`protocol.md`](./protocol.md) §16.
9. **Encrypted packets are dropped observably.** The device decrypts channel traffic. A `MeshPacket` with no decoded payload is never emitted on `packets`, and the engine emits a `ProtocolWarning` at most once a minute.
10. **`PayloadTooLarge` is enforced at enqueue** (`DATA_PAYLOAD_LEN = 233`) and thrown from `send()`. It is never a `SendFailure`. A device `Routing.Error.TOO_LARGE` maps to `SendFailure.Other(routingError = TOO_LARGE)`.
11. **Liveness watchdog.** After Ready, the 60 s budget is reset by every decoded `FromRadio` and decremented by a periodic tick. Exhaustion tears the session down with `TransportError("liveness timeout")`.
12. **Auto-reconnect is opt-in before 1.0.** When enabled, the engine retries recoverable `TransportState.Error`s with backoff and jitter. Pending sends, the dedup set and admin sessions reset between cycles; storage and the supervisor stay live. `ConnectionState.Reconnecting(cause, attempt)` is emitted throughout.
13. **Inbound dedup.** The engine drops a decoded packet whose `(from, to, channel, id)` it has seen in the last 256, so a caller never sees a relay echo twice.
14. **Identity rebind is announced first.** When `MyNodeInfo.my_node_num` differs from the stored value for the identity, the engine emits `MeshEvent.IdentityRebound` before it calls `DeviceStorage.recordOwnNode`, which clears or renumbers per invariant 4.
15. **Storage degradation is fail-open.** If a storage write fails, the engine emits `MeshEvent.StorageDegraded` at most once per connect and skips later writes. In-memory state keeps flowing. The flag resets on the next `connect()`.
16. **Live traffic updates the node DB** (the live node merge in §4.2), so node-list subscribers see changes without a separate `TelemetryApi` subscription.
17. **External config changes are detected.** Unsolicited admin messages from the local device update `channels` and `configBundle` in memory and in storage, and emit `MeshEvent.ExternalConfigChange`.

### 4.4 Concurrency model

- **One engine coroutine** owns all mutable state, with a `Channel<EngineMessage>(UNLIMITED)` inbox.
- **One frame-reader coroutine** per transport collects `transport.frames()` and posts `EngineMessage.FrameRx`. It waits for `TransportState.Connected` before the first collect.
- **One outbound-writer coroutine** drains `Channel<Frame>(UNLIMITED)` and calls `transport.send(frame)` in order.
- **One transport-observer coroutine** collects `transport.state` and posts `EngineMessage.TransportStateChanged`.
- **Timer coroutines** (heartbeat, liveness, presence, reconnect backoff) post ticks to the inbox and never mutate state themselves.
- Public flows are backed by engine-owned shared state:
  - `nodes`: each subscription is seeded with a `Snapshot` through `onSubscription` (the backing `SharedFlow` has `replay = 0`), then `extraBufferCapacity = 256` with `SUSPEND`. Deltas never drop.
  - `packets`: `extraBufferCapacity = 128` with `SUSPEND`. Slow collectors backpressure the engine; if the inbox fills, the engine drops the oldest queued frame and emits `MeshEvent.PacketsDropped(Packets, count)`.
  - `events`: `extraBufferCapacity = 64` with `DROP_OLDEST`. Drops surface as `PacketsDropped(Events, n)`.
  - `fromRadio`: `extraBufferCapacity = 256` with `DROP_OLDEST`, so a slow tap never stalls the engine.
  - `connection`, `ownNode`, `configBundle`, `channels`, `adminSessions`: `StateFlow`, conflated.
- `client.disconnect()` cancels the engine scope; the actor's `finally` flushes storage and closes the transport.

[`threading-model.md`](./threading-model.md) covers dispatchers and transport threads.

---

## 5. Tooling

### 5.1 Build and release

The toolchain follows the sibling `MQTTastic-Client-KMP`; a divergence needs an ADR ([ADR-003](./decisions/003-tooling.md)). Versions live in `gradle/libs.versions.toml` and the Gradle wrapper.

| Tool | Purpose |
|---|---|
| Gradle 9 with Kotlin DSL and the version catalog | Build |
| Convention plugins in `build-logic/convention/` | `meshtastic.kmp.library`, `meshtastic.android.library`, `meshtastic.js.library`, `meshtastic.ios.framework`, `meshtastic.publishing`, `meshtastic.sample.jvm` |
| `explicitApi()` on every library module | Visibility and return types are explicit; protobuf types come from an external artifact |
| `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` | KMP and the Android KMP library plugin |
| Spotless with ktlint and `config/spotless/license-header.txt` | Formatting and the GPL-3.0 header |
| Detekt (`config/detekt/detekt.yml`) | Static analysis, including `ForbiddenImport` architecture rules |
| Detekt `ForbiddenImport` + `:core:verifyModuleBoundary` | Architecture enforcement ([ADR-008](./decisions/008-architecture-enforcement.md)) |
| Kotlin ABI validation (`checkKotlinAbi` / `updateKotlinAbi`) | Per-module dumps: `api/<module>.klib.api` and `api/jvm/<module>.api` |
| Kover | JVM coverage, uploaded to Codecov; `codecov.yml` fails a PR that lowers project coverage |
| Dokka 2 with `Module.md` per module | API docs, published to GitHub Pages by `docs.yml` |
| Vanniktech `maven-publish-plugin` | Maven Central publish and signing |
| `axion-release-plugin` | Git-tag SemVer |
| Develocity + foojay toolchain resolver | Build cache and JDK provisioning |
| SKIE (`co.touchlab.skie`) | Swift bridging on the iOS frameworks: sealed types as enums, `Flow` as `AsyncSequence`, `suspend` as `async throws` ([ADR-007](./decisions/007-ios-distribution.md)) |
| GitHub Actions | JVM, Android, Kotlin/JS and Wasm (Node.js), iOS (macOS), ABI, architecture, coverage and snapshot-publish jobs |
| Renovate | Dependency updates |

KMMBridge is pinned in the catalog but not wired: no aggregated XCFramework or SPM package is published (ADR-007).

### 5.2 Test stack

- `kotlin.test` and Kotest assertions
- Turbine for `Flow`s
- The Kotlin power-assert plugin
- `:testing` fakes (`FakeRadioTransport`, `TestClock`); no mocking library
- `commonTest` runs on JVM, Android host, iOS, and Node.js for `js` and `wasmJs`

### 5.3 Adopt only when justified

Module Graph Assert (graph complexity grows), Dependency Analysis Plugin (leaky `api` exports), kotlinx-benchmark (hot-path regressions).

### 5.4 Rejected

- A second byte vocabulary next to `okio.ByteString`, such as `kotlinx-io`: Wire's protos already put `okio.ByteString` on every proto-typed call.
- An in-memory `StorageProvider` as the default: it would hide invariant 4 behind data loss.
- `kotlin.Result<T>` in the public API: Swift does not bridge it.

---

## 6. Phases

Phases 0 to 5 built the SDK up to 0.1.0, the first Maven Central release. 0.2.0 is the latest release; work since then is in `## [Unreleased]` of [`CHANGELOG.md`](../CHANGELOG.md) and in [`changelog.d/`](../changelog.d/README.md). [`roadmap.md`](./roadmap.md) tracks declared-but-unwired surface.

| Phase | Scope | Status |
|---|---|---|
| 0. Bootstrap | Repo forked from MQTTastic-Client-KMP, convention plugins, CI, ABI and lint gates, ADRs 000 to 008 | Shipped |
| 1. Codec and engine | `WireCodec` with resync, the `MeshEngine` actor, `:testing` fakes | Shipped |
| 2. TCP, storage, first vertical | `transport-tcp`, the two-stage handshake, send tracking, admin RPC, `storage-sqldelight`, `samples/cli`, heartbeat | Shipped |
| 3. BLE, Android, iOS | `transport-ble` on Kable, the parity app on Android and iOS, the SKIE export gate in CI | Shipped |
| 4. Serial | `transport-serial` on jSerialComm, plus usb-serial-for-android ([ADR-016](./decisions/016-usb-serial-for-android-backend.md)) | Shipped |
| 5. API hardening and 0.1.0 | KDoc on every public symbol, `:testing` finalized, the real-radio conformance suite (`cli conformance`), 0.1.0 on Maven Central | Shipped, except the KMMBridge SPM publish and a Dokka coverage gate |
| 6. Coverage completions | Driven by adopter feedback; see the following list | In progress |
| 7. Stabilize to 1.0 | API freeze, final breaking changes, `checkKotlinAbi` hard gate | Not started |

Phase 6 items:

- XModem file transfer: shipped as `RadioClient.files`. Firmware update over XModem is not in scope.
- `transport-websocket`, and the `js` and `wasmJs` targets on `core`, `testing` and `transport-websocket` ([ADR-017](./decisions/017-web-targets.md)): shipped.
- Discovery: mDNS for TCP, `BleScanner`, serial port listing and `TransportTarget.parse`: shipped. Opening a transport from a target stays host code (roadmap R-7).
- `transport-mqtt-proxy`, a side-channel for `MqttClientProxyMessage` built on `org.meshtastic:mqtt-client`, never on an MQTT client of its own: not started.
- A `transport-mqtt` `RadioTransport` for MQTT-only operation: optional, not started.
- Key-verification flows through `events`: `MeshEvent.KeyVerification` is declared but never emitted (roadmap R-17).
- A `host-android` foreground-service helper, and Koin or kotlin-inject sugar: only on adopter demand.

The remote engine (`rpc`, `transport-rpc`, `host-rpc-server`) is post-1.0 and additive: an adapter around the local engine with snapshot-and-delta, versioned envelopes, for decoupled UIs and for web hosts whose node is on BLE, serial or TCP ([`future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md)).

---

## 7. Validation

### 7.1 Local

```shell
./gradlew spotlessCheck detekt build allTests checkKotlinAbi
```

[`testing.md`](./testing.md) covers per-target test tasks.

### 7.2 CI

[`ci-cd.md`](./ci-cd.md) describes the jobs: JVM build and tests with Spotless and detekt, Android host tests, Kotlin/JS and Wasm tests on Node.js, iOS tests and the SKIE export gate (an `xcodebuild` of `samples/parity-app/iosApp`) on macOS, `checkKotlinAbi`, `:core:verifyModuleBoundary`, Kover coverage and the snapshot publish.

### 7.3 Real radios

`cli conformance` runs scripted scenarios against a real node: handshake, text round-trip, admin `getOwner`, traceroute, NodeDB sync and reconnect after a transport drop. [`manual-tests.md`](./manual-tests.md) holds the checklist run before a release.

### 7.4 ABI policy

- **Pre-1.0:** breaking changes allowed with `updateKotlinAbi` and a changelog fragment.
- **1.0 and later:** `checkKotlinAbi` hard-gates; changes are additive only.

[`versioning.md`](./versioning.md) is the authoritative policy.

---

## 8. Locked defaults

A change to any of these needs a PR that justifies it.

| Decision | Default |
|---|---|
| License | GPL-3.0-or-later |
| Group ID | `org.meshtastic` |
| Artifact prefix | `sdk-` |
| Kotlin package root | `org.meshtastic.sdk` |
| Min Android SDK | 26 |
| JVM | JDK 21 toolchain, bytecode 17 |
| Contributor agreement | DCO sign-off only |
| Routine radio failures | Typed sealed outcomes (`SendState.Failed`, `AdminResult`, `FileTransferResult`), never thrown |
| Fatal and programmer errors | `MeshtasticException` (sealed), thrown |
| `Result<T>` in the public API | Forbidden |
| Storage | Required at the builder; no in-memory default |
| Logger | `LogSink.Silent` |
| Engine targets | android, jvm, ios{Arm64, X64, SimulatorArm64}, js, wasmJs |
| Shipped transports | BLE, TCP, serial (Android and JVM), WebSocket |
| Web hosts | Engine in-process over `transport-websocket` ([ADR-017](./decisions/017-web-targets.md)) |
| Remote RPC, MQTT proxy, host modules | Post-1.0, additive |
| Concurrency model | Single-actor engine: one coroutine, `Channel` inbox |
| Node observation | `Flow<NodeChange>` deltas, `Snapshot` first to every subscriber |
| Mesh-delivery retry | The device's job; the SDK never retries on its own timer |
| Convention plugins | `meshtastic.kmp.library`, `meshtastic.android.library`, `meshtastic.js.library`, `meshtastic.ios.framework`, `meshtastic.publishing`, `meshtastic.sample.jvm` |
| Build scaffold source | Forked from `meshtastic/MQTTastic-Client-KMP` |
| Pre-1.0 ABI | Breaking allowed with `updateKotlinAbi` and a changelog fragment |
| 1.0+ ABI | Hard-gated |

---

## 9. References

### 9.1 Protocol

- [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs): the schema, as the published `org.meshtastic:protobufs` artifact
- [`meshtastic/firmware`](https://github.com/meshtastic/firmware): the behavior reference
- [Meshtastic dev docs: Client API](https://meshtastic.org/docs/development/device/client-api/)
- [Meshtastic dev docs: Mesh Algorithm](https://meshtastic.org/docs/overview/mesh-algo/)
- [Meshtastic dev docs: Encryption](https://meshtastic.org/docs/overview/encryption/)
- [Meshtastic dev docs: MQTT](https://meshtastic.org/docs/software/integrations/mqtt/)

### 9.2 Libraries and patterns

- [`meshtastic/MQTTastic-Client-KMP`](https://github.com/meshtastic/MQTTastic-Client-KMP): the scaffold source, same org and toolchain. Its API patterns (an internal transport interface, `StateFlow<ConnectionState>`, `SharedFlow` messages, suspend plus `Flow`, a DSL builder, a logger interface, internal by default) are precedent for the SDK's.
- [Coil 3](https://github.com/coil-kt/coil): KMP capability-based modules
- [Ktor](https://github.com/ktorio/ktor): KMP networking
- [Kable](https://github.com/JuulLabs/kable): KMP BLE
- [SQLDelight](https://github.com/sqldelight/sqldelight): KMP storage
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android): Android USB serial
- [jSerialComm](https://github.com/Fazecast/jSerialComm): JVM and Android serial

### 9.3 Tooling

- [Vanniktech maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
- [axion-release-plugin](https://github.com/allegro/axion-release-plugin)
- [kotlinx-binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator), the predecessor of the built-in Kotlin ABI validation
- [Dokka](https://github.com/Kotlin/dokka)

### 9.4 Reference Android implementation

- [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android): the main port source for the engine (handshake, codec, BLE and serial handling, packet repository). Read it; port deliberately.

---

## 10. Release protocol

1. Fold the changelog fragments into `CHANGELOG.md` (`scripts/changelog.sh fold`).
2. Tag the release; CI publishes the artifacts and Dokka.
3. Summarize what shipped, what is next and the open questions, and wait for a maintainer's go before the next phase.

[`../RELEASING.md`](../RELEASING.md) is the step-by-step procedure.

---

## 11. What Changed From v1 (for reviewers)

### v2.3 (2026-06-11) — 0.2.0 API-state sync

Synchronizes the spec with the 0.2.0 surface: `okio.ByteString` is the single public byte vocabulary (`kotlinx-io` removed — Wire protos force `okio.ByteString` into the public surface for every proto-typed call, and both this SDK and Meshtastic-Android consume the same protobufs artifact, so a second vocabulary bought nothing); lifecycle is suspend-only (no `AutoCloseable`/`close()`; `withConnection { }` helper added); `sendText(text, to, channel, replyId)` parameter order; new `MeshEvent` variants (`MqttProxyMessage`, `XmodemPacket`, `FileInfoReceived`) and `SendFailure.QueueRejected`; `nodes` snapshots seeded per-subscription via `onSubscription` (engine flow `replay = 0`); `DeviceStorage` passkey methods are host-facing only.

### v2.2 (2026-05-10) — Post-audit sync

Full audit of implementation vs spec revealed significant drift. This revision synchronizes the spec with the shipped codebase:

| Was (v2.1) | Now (v2.2) | Why |
|---|---|---|
| JVM target 17 | JVM toolchain 21 | Actual `javaVersion` in `libs.versions.toml` is 21 |
| Android SDK "latest stable" | Compile/Target SDK 36 (pinned) | Match actual `androidCompileSdk = "36"` |
| Builder takes `TransportSpec` | Builder takes `RadioTransport` instance | Actual API takes pre-built transport; `TransportSpec` is informational |
| No auto-reconnect | `AutoReconnectConfig` documented | Shipped in implementation; needs spec coverage |
| No `sendTimeout` / `rpcTimeout` / `presenceTimeout` builder methods | Documented with defaults | Shipped builder knobs |
| No `configBundle` / `channels` StateFlows | Documented as public API | Shipped; consumers depend on them |
| No `sendReaction` / `requestNodeInfo` / `sendRaw` / `connectAndAwaitReady` | Documented | Shipped public surface |
| No `StoreForwardApi` | Documented as sub-API | Shipped |
| `AdminApi` had ~15 methods | ~45 methods documented | Full admin surface shipped |
| `AdminResult` missing `RateLimited` | Added | Shipped variant |
| No `AdminResult` extension functions | `getOrNull`, `map`, `fold`, `onSuccess`, `onFailure`, `getOrThrow` documented | Shipped ergonomic extensions |
| `MeshEvent` had 6 variants | 13+ variants documented (StorageDegraded, DeviceRebooted, IdentityRebound, SecurityWarning, CongestionWarning, ExternalConfigChange) | All shipped |
| `NodeChange` had 4 variants | 6 variants (added WentOffline, CameOnline) | Presence tracking shipped |
| `DeviceStorage` missing passkey + heartbeat methods | 4 new methods documented | Shipped for session resumption + presence |
| `ConfigBundle` missing `deviceUIConfig` | Added | Shipped field |
| `SendOutcome` undocumented | Documented | Shipped type |
| Components table listed `MessageQueue`, `DeferredDecryptBuffer`, `PersistenceCoordinator` as standalone classes | Updated to reflect inline implementation in `MeshEngine` | These were never extracted; spec now matches reality |
| 10 anchor invariants | 17 invariants (liveness watchdog, auto-reconnect, dedup, identity rebind, storage degradation, telemetry merge, external config detection) | All implemented; spec was missing coverage |
| Concurrency model listed 3 coroutines | 4+ coroutines (added transport observer, timer coroutines) | Shipped |
| Test stack: Mokkery | MockK | Actual dependency |
| Appendix A: sketch `jvmToolchain(17)`, `com.android.library`, Okio in commonMain | Actual: `jvmToolchain(21)`, `com.android.kotlin.multiplatform.library`, Kermit + kotlinx-datetime, Dokka + PowerAssert, `-Xjvm-expose-boxed` | Match shipped convention plugin |
| Appendix B: placeholder versions | Actual pinned versions from `libs.versions.toml` | Eliminates guesswork |

### v2.1 (2026-04-17) — MQTTastic-Client-KMP alignment

The Meshtastic org shipped [`MQTTastic-Client-KMP`](https://github.com/meshtastic/MQTTastic-Client-KMP) on 2026-04-16 (`org.meshtastic:mqtt-client:0.1.0`, GPL-3.0). Built by the same maintainers as this SDK. Locked deltas:

| Was (v2) | Now (v2.1) | Why |
|---|---|---|
| Group `org.meshtastic.kmp`, artifact prefix `meshtastic-sdk-` | Group `org.meshtastic`, artifact prefix `sdk-` | Match sibling lib `org.meshtastic:mqtt-client`; `kmp` qualifier is org-redundant |
| Kotlin package root `org.meshtastic.kmp.core` | `org.meshtastic.sdk` | Match `org.meshtastic.mqtt` namespace style |
| Convention plugins `meshtastic.kmp.*` | `meshtastic.kmp.library`, `meshtastic.android.library`, `meshtastic.publishing`, etc. | Finer-grained per-concern plugins; naming reflects target/function |
| Phase −1 governance as blocker | Resolved; ADR + cross-link issue only | Org already accepts KMP libs |
| Phase 0 build-logic from scratch | **Forked verbatim from MQTTastic** (Spotless config, Detekt config, CI workflows, AGENTS.md pattern, Develocity, foojay, vanniktech `publishToMavenCentral()`) | Don't re-derive solved problems |
| Spotless + ktfmt | Spotless + **ktlint** + `licenseHeaderFile` | Match MQTTastic |
| `kotlinx-io` rejected | `kotlinx-io-bytestring` adopted for payloads | Match MQTTastic house style. **Reversed in 0.2.0 (see v2.3 above):** Wire protos force `okio.ByteString` into the public surface for every proto-typed call (both this SDK and Meshtastic-Android consume the same protobufs artifact); a second vocabulary bought nothing — okio standardized, kotlinx-io removed |
| BCV gate from Phase 5 | BCV gate from Phase 0 (`checkKotlinAbi` + initial `updateKotlinAbi`) | MQTTastic enforces day-1; cheap to do same |
| Kover not specified | Kover with `minBound(80)` from Phase 0 | Match MQTTastic |
| Arch-test framework "adopt only when justified" | detekt `ForbiddenImport` + `:core:verifyModuleBoundary` from Phase 0 (an arch-test library was evaluated and dropped — see [ADR-008](decisions/008-architecture-enforcement.md)) | MQTTastic ships an arch-test library; we get the same invariants from tools we already run |
| Single-module vs multi-module unstated | ADR-005 explicitly justifies split (dependency isolation, not code organization) | MQTTastic is single-module — we owe an explanation |
| `transport-mqtt` deferred indefinitely | Promoted to Phase 6; depends on `org.meshtastic:mqtt-client` not own impl | Don't roll our own MQTT — use the org's |

### v2 (vs v1)

| v1 | v2 | Why |
|---|---|---|
| 18+ modules incl. wasm/RPC/hosts/koin | 9 modules MVP, additive later | Drop-in means fewer surfaces; defer non-essentials |
| License: GPL-3.0 with stray Apache-2.0 ADR reference | GPL-3.0, consistent | Internal contradiction resolved |
| `transport-mqtt` as `RadioTransport` | Deferred as `mqtt-proxy` side-channel | MQTT isn't a PhoneAPI transport (§8.18) |
| Wasm targets on storage + MQTT | No wasm in MVP | v1 contradicted itself; cleanest fix is to defer wasm entirely |
| `StateFlow<Map<NodeId, NodeInfo>>` | `Flow<NodeChange>` with Snapshot first | O(N) updates on large meshes are wasteful; deltas align with the RPC shape v1 already proposed |
| `suspend send(): MessageId` | `send(): MessageHandle` with `StateFlow<SendState>` | Routine radio failures shouldn't be exceptions; tracking progress shouldn't require manual event-flow joins |
| Generic `MeshtasticException.NodeUnreachable` for sends | `SendState.Failed(SendFailure.NoRoute)`; admin returns `AdminResult.NodeUnreachable` | Typed expected outcomes |
| `activate(deviceId)` before connect | `activate(TransportIdentity)` before connect | deviceId not knowable pre-handshake on TCP/serial |
| BLE-specific "drain stale buffer" invariant | Transport-agnostic "discard all bytes until matching `config_complete_id`" | TCP/serial can't literally drain OS buffers |
| `transport-serial` (android, jvm) one module via `expect/actual` | `transport-serial-android` + `transport-serial-jvm` separate artifacts | Single artifact composes; consumers depend on one coordinate; per-target backend selected via `expect/actual` |
| `transport-ble` targeting jvm enabled | BLE on android + jvm + ios | Kable ships native JVM backends for macOS, Windows, and Linux; the same library also powers `Meshtastic-Android`. No reason to gate it behind a separate module. |
| MessageQueue with retry/backoff | Tracks state only; no mesh-delivery retry | Device owns OTA retries (§8.14) |
| 7-component target enforced | Honor invariants over count; merged things that only existed to be mockable | Architecture serves correctness, not metrics |
| iOS validation in Phase 5; 0.1.0 right after | iOS validation in Phase 3; 0.1.0 in Phase 5 | Don't release before main consumer paths are validated |
| Manual smoke tests only | Scripted real-radio conformance suite by Phase 5 | Manual tests rot |
| Charter/governance in Phase 0 | **Phase −1 governance gate** *(superseded in v2.1 — see top of §11)* | Avoid building an orphaned SDK |
| `host-android` engine glue in MVP | Host modules deferred entirely | Keeping core pure per user direction; revisit only on adopter demand |
