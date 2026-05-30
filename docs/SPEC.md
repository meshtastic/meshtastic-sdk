# `meshtastic-sdk` — Implementation Plan (v2.2, post-audit)

> **Mission.** A drop-in Kotlin Multiplatform SDK that lets any Android / iOS / JVM-desktop app talk to a Meshtastic radio through a clean, modern Kotlin API.
>
> **Drop-in means:** add one Gradle coordinate, pick a transport, pick a storage, observe `Flow`s, call `suspend` functions. No host plumbing, no DI assumptions, no UI assumptions, no surprises.

---

## Table of Contents

- [0. Read This First](#0-read-this-first)
- [1. Project Identity](#1-project-identity)
- [2. Module Layout (lean)](#2-module-layout-lean)
- [3. Public API (the contract)](#3-public-api-the-contract)
- [4. Internal Architecture](#4-internal-architecture)
- [5. Tooling](#5-tooling)
- [6. Phases](#6-phases)
- [7. Validation](#7-validation)
- [8. Locked Defaults (override only with PR justification)](#8-locked-defaults-override-only-with-pr-justification)
- [9. References](#9-references)
- [10. Phase Completion Protocol](#10-phase-completion-protocol)
- [11. What Changed From v1 (for reviewers)](#11-what-changed-from-v1-for-reviewers)
- [Appendix A — Convention plugin sketch](#appendix-a--convention-plugin-sketch)
- [Appendix B — `gradle/libs.versions.toml` skeleton](#appendix-b--gradlelibsversionstoml-skeleton)

---

## 0. Read This First

### What this SDK is
A faithful Kotlin client of the Meshtastic device PhoneAPI (the host-side wire protocol — see §8 of the v1 plan for the full protocol reference, retained verbatim as `docs/protocol.md`). It owns: framing, handshake, state model, send tracking, admin RPC, telemetry, routing, decoding/encoding. It is consumed in-process by app code on Android, iOS, and JVM desktop.

### What this SDK is NOT (MVP)
- **Not remote/RPC.** No wasm target, no kotlinx-rpc server, no browser story. These can be added later as a non-breaking adapter.
- **Not a host.** No bound `MeshtasticService`, no foreground-service helper, no notification policy, no WorkManager, no permission flow. Apps wire those.
- **Not a UI library** and not a DI container.
- **Not an MQTT bridge.** MQTT is *not* a PhoneAPI transport (see v1 §8.18); a `MqttClientProxy` module may land later as a side-channel, not as a `RadioTransport`.
- **Not a fork of `Meshtastic-Android`.** We're building a clean KMP SDK, not lifting the Android app's UI/repository/DI stack. Port what's useful from sibling org repos, write what isn't.

### Hard rules
1. **License: GPL-3.0**, consistent across repo, README, ADRs, and POMs. (Note: this is the same license as `Meshtastic-Android`, which constrains downstream adopters to GPL-compatible terms — accepted trade-off given the project's existing GPL ecosystem.)
2. **No `java.*` or `android.*` in `commonMain`.** Use `kotlinx-io.bytestring` for public byte payloads (Okio is acceptable transport-internally); `kotlinx.coroutines.sync.Mutex` is allowed only outside the engine package (see rule 5 / [ADR-002](./decisions/002-architecture.md)); use atomicfu for atomics and kotlinx-datetime for time.
3. **No `kotlin.Result<T>` in any public API** (poor Swift bridging).
4. **Public API uses three response shapes deliberately:**
   - `suspend fun` *throwing* sealed `MeshtasticException` — for fatal/programmer errors and transport unreachable
   - **Typed sealed outcomes** (`SendState`, `AdminResult`) — for expected radio failures (NAK, timeout, busy)
   - `Flow` / `StateFlow` — for streams and reactive state
5. **Single writer to engine state.** `MeshEngine` is implemented as an actor (one coroutine drains a `Channel<EngineMessage>`). All state mutation goes through it.
6. **Pre-handshake bytes are never trusted.** The handshake nonce in `config_complete_id` is the only signal that the session is real. Everything before it is discarded — applies to BLE, TCP, and serial uniformly.
7. **Storage is keyed by `TransportIdentity`**, derived deterministically from `TransportSpec`. Activated before `transport.connect()`. Identity is stable per consumer-chosen transport config; the `NodeNum` learned at handshake is *recorded* in storage but is not the storage key. (Identity-fragmentation caveat for TCP/HTTP and the NodeNum-mismatch reset rule are documented at the `TransportIdentity` definition in §3 and invariant 4 in §4.3.)
8. **Multi-radio = one `RadioClient` per radio.** A single `RadioClient` owns exactly one `TransportSpec` and one `DeviceStorage` for its lifetime. Hosts that need to talk to N radios concurrently instantiate N clients (each with its own `Builder.storage(...)` and `Builder.transport(...)`); they share nothing. The SDK does not multiplex one client over multiple transports — the engine actor's single-writer invariant (rule 5) and storage's per-identity activation (rule 7) both presume a 1:1 client↔radio relationship.
9. **Every public symbol gets a KDoc.** Dokka coverage is a CI gate from Phase 5.
10. **Pre-1.0:** breaking changes allowed; require `updateKotlinAbi` + CHANGELOG entry. **1.0+:** binary-compatibility-validator hard-gates.

### Authoritative protocol sources
- **Schema:** [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs) — vendored as `proto/src/protobufs` git submodule.
- **Behavior reference:** [`meshtastic/firmware`](https://github.com/meshtastic/firmware) (read-only).
- **`Meshtastic-Android`** — port what's useful (codec, handshake FSM, BLE handler, encryption helpers) into `commonMain` / `androidMain` as appropriate.
- **Real device** — required from Phase 2 onward.

The protocol reference (handshake, framing, `MeshPacket`, PortNums, admin, encryption, MQTT) lives in `docs/protocol.md`, lifted verbatim from §8 of the v1 plan. Treat it as the wire-level bible.

---

## 1. Project Identity

| | |
|---|---|
| **Repo** | `meshtastic/meshtastic-sdk` |
| **Group ID** | `org.meshtastic` *(matches sibling lib `org.meshtastic:mqtt-client`)* |
| **Artifact prefix** | `sdk-` (e.g., `sdk-core`, `sdk-transport-ble`) |
| **Kotlin package root** | `org.meshtastic.sdk` (mirrors `org.meshtastic.mqtt` in MQTTastic-Client-KMP) |
| **Scaffold source** | Fork build/config/CI from [`meshtastic/MQTTastic-Client-KMP`](https://github.com/meshtastic/MQTTastic-Client-KMP) — same org, same toolchain, just shipped |
| **License** | **GPL-3.0** |
| **Contributor agreement** | DCO (`Signed-off-by:`); no separate CLA |
| **Targets (per module subset)** | `androidTarget()`, `jvm()`, `iosArm64()`, `iosX64()`, `iosSimulatorArm64()` |
| **Min Android SDK** | 26 |
| **Compile/Target Android SDK** | 36 (latest stable at time of writing) |
| **JVM toolchain** | 21 |
| **Kotlin** | 2.3.21 (pinned in `gradle/libs.versions.toml`) |
| **Versioning** | SemVer via `axion-release-plugin` (git tags) |

---

## 2. Module Layout (lean)

```
meshtastic-sdk/
├── build-logic/convention/             # Convention plugins
│
├── proto/                              # Wire-generated DTOs — PUBLIC (per ADR-001)
│                                       #   targets: android, jvm, ios{Arm64,X64,SimulatorArm64}
│                                       #   future: wasmJs (post-1.0; see docs/future/wasm-rpc-roadmap.md)
│                                       #   Consumers import org.meshtastic.proto.* directly.
│
├── core/                               # PUBLIC FACADE + ENGINE
│   - RadioClient, Builder
│   - sealed ConnectionState/SendState/SendFailure/MeshtasticException
│   - SDK-shaped types (NodeChange, MessageHandle, TransportSpec)
│   - Value-class IDs: NodeId, ChannelIndex, MessageId
│   - Operation helpers: sendText(...), traceRoute(...), telemetry/admin sub-APIs
│   - WireCodec (framing + Wire encode/decode)
│   - MeshEngine (actor), MeshState, HandshakeMachine,
│     CommandDispatcher, MessageQueue, DeferredDecryptBuffer,
│     PersistenceCoordinator
│   - StorageProvider/DeviceStorage interfaces
│   - RadioTransport interface (+ Frame, TransportIdentity)
│   - LogSink, Clock, in-memory Storage IS NOT PROVIDED here
│   - api(project(":proto"))   so org.meshtastic.proto.* is transitive
│
├── transport-ble/                      # Kable.    targets: android, jvm, ios
├── transport-tcp/                      # Ktor.     targets: android, jvm, ios
├── transport-serial/                   # usb-serial-for-android (android) +
│                                       # jSerialComm (jvm); unified via expect/actual
│
├── storage-sqldelight/                 # Default real storage.    targets: android, jvm, ios
│
├── bom/                                # Maven BOM aligning published artifact versions
│
├── testing/                            # FakeRadioClient, FakeRadioTransport,
│                                       # FakeStorage (in-memory; explicitly NOT a default),
│                                       # packet builders, deterministic clock helpers,
│                                       # Turbine-compatible Flow harnesses
│
├── samples/
│   ├── cli/                            # JVM main; TCP transport; first demoable artifact
│   ├── parity-app/                     # Compose Multiplatform parity sample (Android + iOS + JVM desktop) over TCP
│   └── parity-android-app/             # Android-only sample app
│
└── docs/
    ├── protocol.md                     # The §8 wire protocol reference, verbatim
    ├── architecture/                   # Mermaid diagrams (engine, FSM, topologies)
    ├── decisions/                      # ADRs
    └── (Dokka site published to gh-pages)
```

**Per-target subset:** every module declares only the targets it can compile for. CI matrix verifies.

**Why multi-module here, when sibling `meshtastic/MQTTastic-Client-KMP` ships as a single `:library` module?**
MQTTastic has 2 transports (TCP, WS) over a single Ktor dependency, with no platform-only native libraries. We have 4+ transports (BLE, TCP, Serial-Android, Serial-JVM, eventually MQTT-proxy) with **hard platform-availability differences and disjoint heavyweight dependencies** (Kable, usb-serial-for-android, jSerialComm). Bundling them into one module would force every consumer to pull every transport's transitive deps and would fail the per-target subset rule. Multi-module is justified by **dependency isolation** — not by code organization. Internally, each module still uses MQTTastic's `nonWebMain`-style intermediate source-set pattern where applicable.

**Deliberately deferred (post-1.0 or non-breaking adds):**
- `transport-mqtt-proxy` — `MqttClientProxyMessage` plumbing
- `host-android` — bound service + lifecycle helpers
- `host-jvm` — embedded engine helper
- `rpc/`, `rpc-adapter/`, `host-rpc-server/`, `transport-rpc/`, `samples/wasm-browser/`
- Optional DI sugar (`koin/`, `kotlin-inject/`)

---

## 3. Public API (the contract)

### 3.1 `org.meshtastic.sdk` (package root for the public API; module `:core` → artifact `org.meshtastic:sdk-core`)

> **ADR-001:** the SDK exposes Wire-generated protobuf types directly. Anywhere the API below references a protocol payload (`MeshPacket`, `NodeInfo`, `Config`, `User`, `Channel`, `Position`, `Telemetry`, `AdminMessage`, `PortNum`, etc.), that is the **Wire-generated type from `org.meshtastic.proto.*`**, not a hand-rolled mirror. The SDK curates only what does not exist in the proto schema (lifecycle, transport, IDs, send tracking).

```kotlin
public class RadioClient internal constructor(/* ... */) : AutoCloseable {

    public val connection: StateFlow<ConnectionState>

    /** The local node (NodeInfo for our own NodeNum), available after handshake. */
    public val ownNode: StateFlow<NodeInfo?>

    /** Most recently committed ConfigBundle for the active session; null until Connected. */
    public val configBundle: StateFlow<ConfigBundle?>

    /** Channel list for the active session; null until Connected. Updated on setChannel success. */
    public val channels: StateFlow<List<Channel>?>

    /** Per-node deltas. Late subscribers receive a Snapshot first, then live changes. */
    public val nodes: Flow<NodeChange>

    /**
     * Decoded inbound packets. `MeshPacket.decoded` (the `Data` `oneof`) is always populated
     * before emission; the engine handles channel decryption transparently. Filter by
     * `decoded.portnum` to match specific app payloads.
     */
    public val packets: Flow<MeshPacket>

    /**
     * Side-channel events: errors, queue status, client notifications, key-verification prompts,
     * drop notifications, identity-rebind signals, congestion warnings, external config changes.
     */
    public val events: Flow<MeshEvent>

    /** Pull a snapshot on demand (cheap; backed by engine state). */
    public suspend fun nodeSnapshot(): Map<NodeId, NodeInfo>

    /** Request a remote node to send its NodeInfo. */
    public fun requestNodeInfo(node: NodeId): MessageHandle

    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun connect()

    /** Idempotent; never throws. */
    public suspend fun disconnect()

    /** Blocking close for AutoCloseable / use {} conformance. Delegates to disconnect via runBlocking. */
    override fun close()

    /**
     * Enqueue an outbound packet. Returns immediately with a handle whose [MessageHandle.state]
     * tracks lifecycle: Queued -> Sent -> Acked/Delivered/Failed.
     * The engine fills in `id`, `from`, and (for unencrypted local sends) channel hash;
     * callers may override these on the supplied [MeshPacket] if they know what they're doing.
     * Throws only on programmer error (e.g., not connected, payload too large).
     */
    @Throws(MeshtasticException::class)
    public fun send(packet: MeshPacket): MessageHandle

    /** Convenience: wraps text in TEXT_MESSAGE_APP. */
    public fun sendText(text: String, channel: ChannelIndex = ChannelIndex(0), to: NodeId = NodeId.BROADCAST): MessageHandle

    /** Convenience: send an emoji reaction to an existing message. */
    public fun sendReaction(emoji: String, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), replyId: Int): MessageHandle

    /** Typed send overload: build Data(portnum, payload) and call send(). */
    public suspend fun send(portnum: PortNum, payload: ByteArray, to: NodeId = NodeId.BROADCAST, channel: ChannelIndex = ChannelIndex(0), wantAck: Boolean = false, hopLimit: Int? = null): MessageHandle

    /** Buffer payload overload (consumes the kotlinx.io.Buffer). */
    public suspend fun send(portnum: PortNum, payload: kotlinx.io.Buffer, ...): MessageHandle

    /** Low-level escape hatch: send a raw ToRadio frame directly. */
    public fun sendRaw(frame: ToRadio)

    public val admin: AdminApi
    public val telemetry: TelemetryApi
    public val routing: RoutingApi
    public val storeForward: StoreForwardApi

    public companion object { public fun Builder(): Builder }

    public class Builder {
        public fun transport(transport: RadioTransport): Builder        // required; takes a pre-built transport instance
        public fun storage(provider: StorageProvider): Builder          // required (no default)
        public fun logger(sink: LogSink): Builder                       // default: LogSink.Silent
        public fun clock(clock: kotlin.time.Clock): Builder             // default: Clock.System
        public fun coroutineContext(ctx: CoroutineContext): Builder     // default: SupervisorJob + Default
        public fun autoSyncTimeOnConnect(enabled: Boolean): Builder     // default: true
        public fun disableBleHeartbeat(): Builder                       // default: heartbeat-on-BLE enabled
        public fun protocolLogging(level: LogLevel, redactor: PayloadRedactor = PayloadRedactor.Default): Builder
        public fun sendTimeout(duration: Duration): Builder             // default: 30s — per-send ACK timeout
        public fun rpcTimeout(duration: Duration): Builder              // default: 30s — per-RPC admin timeout
        public fun presenceTimeout(timeout: Duration): Builder          // default: 2h — node online/offline window
        public fun autoReconnect(                                       // default: disabled (1.0 will flip to enabled)
            enabled: Boolean = true,
            initialBackoff: Duration = 1.seconds,
            maxBackoff: Duration = 60.seconds,
            maxAttempts: Int? = null,
            backoffMultiplier: Double = 2.0,
            jitter: Double = 0.2,
        ): Builder
        public fun autoReconnect(config: AutoReconnectConfig): Builder
        public fun build(): RadioClient
    }
}

/** Connect and suspend until the handshake settles; returns the resolved ConfigBundle. */
public suspend fun RadioClient.connectAndAwaitReady(timeout: Duration = 30.seconds): ConfigBundle

/** DSL form of send: `client.send { text("hello"); to(node); wantAck() }`. */
public suspend fun RadioClient.send(block: SendBuilder.() -> Unit): MessageHandle
```

**`AutoReconnectConfig`** — configures the engine's built-in auto-reconnect supervisor:

```kotlin
public data class AutoReconnectConfig(
    val enabled: Boolean = true,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 60.seconds,
    val maxAttempts: Int? = null,           // null = retry indefinitely
    val backoffMultiplier: Double = 2.0,
    val jitter: Double = 0.2,              // ±20% symmetric randomization
) {
    public companion object {
        public val Disabled: AutoReconnectConfig = AutoReconnectConfig(enabled = false)
    }
}
```

Backoff formula: `delay(n) = min(initialBackoff * backoffMultiplier^(n-1), maxBackoff) * (1 ± jitter * random())`. Uses `kotlinx.coroutines.delay` for virtual-time compatibility with `runTest`.

public class MessageHandle internal constructor(
    public val id: MessageId,
    public val state: StateFlow<SendState>,
) {
    /**
     * Suspends until terminal ([SendState.Acked], [SendState.Delivered], or [SendState.Failed]).
     *
     * **Disconnect:** if the engine disconnects before a terminal state, `state` resolves to
     * `Failed(Disconnected)` and `await()` returns the corresponding [SendOutcome].
     *
     * **Cancellation:** if the *caller's* coroutine is cancelled while suspended in `await()`,
     * the function rethrows `CancellationException`; the underlying handle is **unaffected**.
     * Use [cancel] to actively withdraw the send.
     */
    public suspend fun await(): SendOutcome

    /**
     * Best-effort cancel. Idempotent. Behavior by current state:
     * - `Queued`: removed from the host outbound queue; `state` becomes `Failed(Cancelled)`.
     * - `Sent` or later: no effect on the radio; `state` is unchanged.
     */
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
    public data object NoRoute : SendFailure                           // Routing.NO_ROUTE
    public data object MaxRetransmit : SendFailure
    public data object Timeout : SendFailure
    public data object DutyCycleLimit : SendFailure
    public data object Disconnected : SendFailure                      // transport dropped mid-send
    public data object HandshakeFailed : SendFailure                   // session never reached Ready
    public data object Cancelled : SendFailure                         // MessageHandle.cancel() called pre-Sent
    public data object IdCollision : SendFailure                       // duplicate packet id rejected
    public data object AckTimeout : SendFailure                        // per-send ACK timeout (unicast want_ack only)
    public data class Other(val routingError: Routing.Error) : SendFailure   // Wire enum from :proto
    public data class Unknown(val message: String) : SendFailure
}
// NOTE: Payload-too-large is enforced *before* enqueue: `send()` throws `MeshtasticException.PayloadTooLarge`
// directly. It is therefore not a SendFailure case (a handle is never returned in that situation).

public sealed interface MeshEvent {
    public data class QueueStatusChanged(val status: QueueStatus) : MeshEvent
    public data class Notification(val notification: ClientNotification) : MeshEvent
    public data class TransportError(val error: MeshtasticException.Transport) : MeshEvent
    public data class ProtocolWarning(val message: String, val details: Map<String, Any?> = emptyMap()) : MeshEvent
    public data class KeyVerification(val prompt: KeyVerificationPrompt) : MeshEvent
    /** Engine-side backpressure: the named flow dropped `count` items. See §4.4. */
    public data class PacketsDropped(val flow: DroppedFlow, val count: Int) : MeshEvent
    /** Storage backend encountered a write failure; engine continues in-memory for the session. */
    public data class StorageDegraded(val reason: String) : MeshEvent
    /** Device sent `FromRadio.rebooted = true`; session state is stale. */
    public data class DeviceRebooted : MeshEvent
    /**
     * Device identity changed between sessions (factory reset / radio swap / hostname rebound).
     * Emitted before the engine clears storage, so subscribers can react.
     */
    public data class IdentityRebound(val previousNodeNum: NodeId, val newNodeNum: NodeId, val reason: String) : MeshEvent
    /** Firmware-reported security advisory (duplicated public key, low-entropy key). */
    public sealed interface SecurityWarning : MeshEvent {
        public data object DuplicatedPublicKey : SecurityWarning
        public data object LowEntropyKey : SecurityWarning
    }
    /** Channel utilization / air_util_tx crossed a threshold; level changed. */
    public data class CongestionWarning(val metrics: CongestionMetrics) : MeshEvent
    /** External client modified channels/config on this device (unsolicited admin push). */
    public data class ExternalConfigChange(val kind: ExternalChangeKind) : MeshEvent
}

public enum class DroppedFlow { Packets, Events }
public enum class ExternalChangeKind { CHANNEL, CONFIG, MODULE_CONFIG }

public sealed interface NodeChange {
    /**
     * First emission to every new subscriber; never emitted again on the same subscription.
     * Implemented by replaying the engine's current `Map<NodeId, NodeInfo>` on `collect()` (single-replay),
     * then stitching live deltas.
     */
    public data class Snapshot(val nodes: Map<NodeId, NodeInfo>) : NodeChange
    public data class Added(val node: NodeInfo) : NodeChange
    public data class Updated(val node: NodeInfo, val changed: Set<NodeField>) : NodeChange
    public data class Removed(val nodeId: NodeId) : NodeChange
    /** Node has not been heard within the configured `presenceTimeout`. */
    public data class WentOffline(val nodeId: NodeId, val lastHeardSec: Int) : NodeChange
    /** A previously-offline node sent a frame. */
    public data class CameOnline(val nodeId: NodeId) : NodeChange
}

public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data class Connecting(val attempt: Int) : ConnectionState
    public data class Configuring(val phase: ConfigPhase, val progress: Float) : ConnectionState
    public data object Connected : ConnectionState
    public data class Reconnecting(val cause: MeshtasticException, val attempt: Int) : ConnectionState
}
// NOTE: There is no DeviceSleep state. Devices do not announce sleep on the wire.
// Sleep timing is observable via `Config.power.ls_secs` from the handshake snapshot.

// Extension properties on ConnectionState:
public val ConnectionState.isUsable: Boolean          // true only when Connected
public val ConnectionState.isInProgress: Boolean      // true for Connecting/Configuring/Reconnecting
public val ConnectionState.statusMessage: String      // human-readable description

public sealed interface TransportSpec {
    public val identity: TransportIdentity
    public data class Ble(val address: String) : TransportSpec
    public data class Tcp(val host: String, val port: Int = 4403) : TransportSpec
    public data class Http(val baseUrl: String) : TransportSpec        // PhoneAPI HTTP, see protocol.md §4
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
// IDENTITY-FRAGMENTATION CAVEAT (TCP/HTTP):
// Identity is computed from the *literal* host string the consumer supplies. Connecting to the
// same physical radio via "meshtastic.local" vs "192.168.1.42" produces two distinct identities,
// each with its own storage. Consumers wanting a single store across address changes should:
//   (a) canonicalise the host themselves (e.g. resolve mDNS once, reuse the IP literal), OR
//   (b) catch the post-handshake `recordOwnNode` callback and merge stores keyed by `NodeNum`.
// The SDK does not perform DNS canonicalisation — DNS resolution is platform/network-dependent
// and could yield a different cache key on every connect. See §4.3 invariant 4 for the
// node-num-mismatch reset rule that bounds this risk.

public sealed class MeshtasticException(message: String, cause: Throwable? = null)
    : Exception(message, cause) {
    /** Diagnostic context: transport that produced this failure, if known. */
    public var transportIdentity: TransportIdentity?
    /** Diagnostic context: short operation tag (e.g. "connect", "engine.disconnect"). */
    public var operation: String?

    public class Transport(reason: String, cause: Throwable? = null) : MeshtasticException(reason, cause)
    public class Protocol(reason: String, cause: Throwable? = null) : MeshtasticException(reason, cause)
    public class HandshakeTimeout(public val stage: String) :
        MeshtasticException("Handshake timed out during stage: $stage")
    public class StorageUnavailable(message: String = "Storage unavailable", cause: Throwable? = null)
        : MeshtasticException(message, cause)
    public class FirmwareTooOld(public val required: Int, public val present: Int)
        : MeshtasticException("Firmware requires newer client (need $required, have $present)")
    public class NotConnected : MeshtasticException("Client not connected")
    public class AlreadyConnected : MeshtasticException("Client already connected")
    public class PayloadTooLarge(public val maxBytes: Int)
        : MeshtasticException("Payload exceeds $maxBytes bytes")

    public companion object {
        /** Attach diagnostic context (transport identity, operation tag). Returns the same instance. */
        public fun <T : MeshtasticException> tag(error: T, transportIdentity: TransportIdentity? = null, operation: String? = null): T
    }
}

/** Type-safe wrappers over the protobuf `uint32` fields used as IDs. Used in operation signatures. */
@JvmInline public value class NodeId(public val raw: Int) {
    public companion object {
        public val LOCAL: NodeId = NodeId(0)
        public val BROADCAST: NodeId = NodeId(0xFFFFFFFF.toInt())
    }
}
@JvmInline public value class ChannelIndex(public val raw: Int)
@JvmInline public value class MessageId(public val raw: Int)
```

**Why three response shapes?** Throwing for transport-level / programmer errors keeps `try { client.connect() }` natural. Typed `SendState`/`AdminResult` for routine radio outcomes — NAKs and timeouts are *expected* on a flaky mesh and shouldn't unwind the stack. `Flow` for streams.

**Why `NodeChange` instead of `StateFlow<Map<NodeId, NodeInfo>>`?** A 200-node mesh with frequent telemetry would emit a 200-entry map on every update. Deltas are O(1); subscribers can fold to a `StateFlow` themselves. The first emission to every new subscriber is `Snapshot` so late subscribers aren't stranded.

**Why `Flow<MeshPacket>` instead of a sealed `MeshMessage` hierarchy?** Per ADR-001, the protobuf schema is the domain model. Apps switch on `packet.decoded.portnum` (Wire's generated `PortNum` enum) and read `packet.decoded.payload` as the matching protobuf type — no parallel sealed hierarchy to maintain or lag behind firmware.

### 3.2 Sub-API namespaces

> All return/parameter types here named without a package prefix are **Wire-generated types** from `org.meshtastic.proto.*`. The SDK does not redeclare them.

```kotlin
public interface AdminApi {
    /** Return an AdminApi that targets a remote node over the mesh. */
    public fun forNode(dest: NodeId): AdminApi

    // ── Device info ──
    public suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata>
    public suspend fun getDeviceConnectionStatus(): AdminResult<DeviceConnectionStatus>
    public suspend fun getRemoteHardwarePins(): AdminResult<NodeRemoteHardwarePinsResponse>

    // ── Configs ──
    public suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config>
    public suspend fun setConfig(config: Config): AdminResult<Unit>
    public suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig>
    public suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit>

    // ── Owner ──
    public suspend fun getOwner(): AdminResult<User>
    public suspend fun setOwner(user: User): AdminResult<Unit>

    // ── Channels ──
    public suspend fun getChannel(index: ChannelIndex): AdminResult<Channel>
    public suspend fun setChannel(channel: Channel): AdminResult<Unit>
    public suspend fun listChannels(): AdminResult<List<Channel>>

    // ── Node management ──
    public suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit>
    public suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit>
    public suspend fun toggleMuted(node: NodeId): AdminResult<Unit>
    public suspend fun removeNode(node: NodeId): AdminResult<Unit>

    // ── Position ──
    public suspend fun setFixedPosition(position: Position): AdminResult<Unit>
    public suspend fun removeFixedPosition(): AdminResult<Unit>

    // ── Device UI Config ──
    public suspend fun getUIConfig(): AdminResult<DeviceUIConfig>
    public suspend fun storeUIConfig(config: DeviceUIConfig): AdminResult<Unit>

    // ── Canned Messages / Ringtone ──
    public suspend fun getCannedMessages(): AdminResult<String>
    public suspend fun setCannedMessages(messages: String): AdminResult<Unit>
    public suspend fun getRingtone(): AdminResult<String>
    public suspend fun setRingtone(rtttl: String): AdminResult<Unit>

    // ── Ham radio ──
    public suspend fun setHamMode(params: HamParameters): AdminResult<Unit>

    // ── DFU / File management ──
    public suspend fun enterDfuMode(): AdminResult<Unit>
    public suspend fun deleteFile(path: String): AdminResult<Unit>

    // ── Backup / Restore ──
    public suspend fun backupPreferences(location: AdminMessage.BackupLocation = FLASH): AdminResult<Unit>
    public suspend fun restorePreferences(location: AdminMessage.BackupLocation = FLASH): AdminResult<Unit>
    public suspend fun removeBackupPreferences(location: AdminMessage.BackupLocation = FLASH): AdminResult<Unit>

    // ── Contacts / Key verification ──
    public suspend fun addContact(contact: SharedContact): AdminResult<Unit>
    public suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit>

    // ── OTA / Sensor / Simulator ──
    public suspend fun rebootOta(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun otaRequest(event: AdminMessage.OTAEvent): AdminResult<Unit>
    public suspend fun setSensorConfig(config: SensorConfig): AdminResult<Unit>
    public suspend fun exitSimulator(): AdminResult<Unit>

    // ── Display ──
    public suspend fun setScale(scale: Int): AdminResult<Unit>
    public suspend fun sendInputEvent(event: AdminMessage.InputEvent): AdminResult<Unit>

    // ── Lifecycle ──
    public suspend fun reboot(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun shutdown(after: Duration = Duration.ZERO): AdminResult<Unit>
    public suspend fun factoryReset(preserveBleBonds: Boolean = true): AdminResult<Unit>
    public suspend fun nodeDbReset(): AdminResult<Unit>

    // ── Time ──
    public suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit>
    public suspend fun setTime(at: Instant? = null): AdminResult<Unit>

    // ── Transactional batched writes ──
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

// AdminResult extension functions:
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
    public suspend fun requestLocalStats(): AdminResult<LocalStats>
    public fun observe(node: NodeId): Flow<Telemetry>
}

public interface RoutingApi {
    public suspend fun traceRoute(dest: NodeId, hopLimit: Int = 7): AdminResult<RouteDiscovery>
    public suspend fun requestNeighborInfo(node: NodeId = NodeId.LOCAL): AdminResult<NeighborInfo>
}

public interface StoreForwardApi {
    public suspend fun requestHistory(node: NodeId, window: Duration): AdminResult<Unit>
    public fun messages(): Flow<MeshPacket>
}
```

Firmware update is omitted from MVP — XModem file transfer is a Phase 6 add.

### 3.3 Storage contract

```kotlin
public interface StorageProvider {
    /** Activated before transport.connect(). Identity is derived from TransportSpec. */
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
     * Records the NodeNum the device reported for this transport identity. Audit trail and
     * factory-reset detector. If `nodeNum` differs from the stored value, implementations MUST
     * atomically `clear()` and then persist the new tuple before returning.
     */
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String)

    public suspend fun clear()
    override fun close()

    /** Persist / load the session passkey for admin RPC resumption across sessions. */
    public suspend fun saveSessionPasskey(passkey: SessionPasskey)
    public suspend fun loadSessionPasskey(): SessionPasskey?

    /** Persist / load per-node heartbeat timestamps for presence tracking across process death. */
    public suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long)
    public suspend fun loadHeartbeats(): Map<NodeId, Long>
}

/** Aggregate of everything received during the configure-handshake. */
public data class ConfigBundle(
    public val myInfo: MyNodeInfo,
    public val metadata: DeviceMetadata,
    public val configs: List<Config>,
    public val moduleConfigs: List<ModuleConfig>,
    public val deviceUIConfig: DeviceUIConfig? = null,
)

/** Persisted session passkey with absolute expiry. */
public data class SessionPasskey(
    public val bytes: kotlinx.io.bytestring.ByteString,
    public val expiresAtEpochMs: Long,
)
```

**No in-memory default.** `:testing` provides one explicitly named `InMemoryStorage` for tests. Production callers must wire `:storage-sqldelight` or roll their own.

### 3.4 Transport contract

```kotlin
public interface RadioTransport {
    public val identity: TransportIdentity
    public suspend fun connect()
    public suspend fun disconnect()
    public suspend fun send(frame: Frame)
    public fun frames(): Flow<Frame>                                 // cold; one collector
    public val state: StateFlow<TransportState>                      // engine observes for connection lifecycle
}

public sealed interface TransportState {
    public data object Disconnected : TransportState
    public data object Connecting : TransportState
    /**
     * Transport link is up but the platform is negotiating encryption / pairing with the device.
     * Emitted by transports that do encrypted I/O (notably BLE) when the OS-level pairing dialog
     * may be visible to the user. The transport's `connect()` is still suspended — the engine has
     * not yet started the handshake clock, so callers can render "Confirm pairing on your device"
     * UI without racing the handshake timeout.
     *
     * Transition flow: [Connecting] → [Bonding] → [Connected]
     */
    public data object Bonding : TransportState
    public data object Connected : TransportState
    public data class Error(val cause: Throwable, val recoverable: Boolean) : TransportState
}

public class Frame(public val bytes: ByteString)                     // kotlinx.io.bytestring.ByteString (matches §5.4 + MQTTastic-Client-KMP)
```

### 3.5 Send DSL

```kotlin
@DslMarker public annotation class MeshSendDsl

@MeshSendDsl
public class SendBuilder internal constructor() {
    public fun to(nodeId: NodeId)
    public fun channel(channel: ChannelIndex)
    public fun wantAck(value: Boolean = true)
    public fun hopLimit(hops: Int?)
    public fun text(text: String)                              // TEXT_MESSAGE_APP payload
    public fun data(portnum: PortNum, bytes: ByteArray)        // arbitrary portnum + raw bytes
    public fun position(latLng: LatLng)                        // POSITION_APP payload
    public fun proto(packet: MeshPacket)                       // escape hatch — use packet verbatim
}

// Exactly one of text/data/position/proto must be called per builder.
// After proto(), convenience setters (to, channel, wantAck, hopLimit) throw IllegalStateException.
```

### 3.6 Logging

```kotlin
public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, cause: Throwable?)
    public companion object { public val Silent: LogSink = LogSink { _, _, _, _ -> } }
}
```

SDK never depends on Kermit / Timber / SLF4J. Apps wire whatever they want.

---

## 4. Internal Architecture

### 4.1 The actor

```
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

All state mutation happens on the engine coroutine. No `Mutex`, no atomics-on-state. The actor IS the synchronization primitive.

### 4.2 Components (kept lean; merged when they only existed to be mockable)

| Component | Responsibility |
|---|---|
| `WireCodec` | Pure encode/decode `ToRadio` ↔ framed bytes ↔ `FromRadio`. Resync logic per v1 §8.2. `FrameDecoder` inner class for streaming byte-at-a-time TCP/Serial decode. No IO. |
| `WireFraming` | Single source of truth for wire-framing constants: sync bytes (`0x94 0xC3`), `HEADER_SIZE`, `MAX_PAYLOAD_SIZE`, `MAX_FRAME_ON_WIRE`. Shared by all stream transports. |
| `RadioTransport` (interface) | Per §3.4. Implementations in transport modules. |
| Handshake FSM | Stage 1 → 100 ms settle → heartbeat → 100 ms settle → Stage 2 → seed `session_passkey` via `get_owner_request`. The FSM is implemented inline in `MeshEngine` (not a standalone class as originally planned) using the `HandshakeStage` enum + per-stage envelope processors. Stage 1 has a one-shot retry at the half-budget mark. Stage 2 uses a sliding-timeout watchdog (progress counter + hard cap) instead of a fixed deadline. |
| `MeshState` | Immutable snapshot: `Map<NodeId, NodeInfo>`, `ownNode`, `channels`, `ConfigBundle`, monotonic version counter. Single-writer (engine actor). Replaced on mutation, never mutated in place. |
| `CommandDispatcher` | Registers `(requestId, ResponseKind, CompletableDeferred)` triples. Completes or times out on matching inbound packets. Handles routing-error classification for RPC calls. |
| Send tracking | Tracks outbound `MessageHandle`s via `pendingSends: Map<MessageId, MutableStateFlow<SendState>>`. Updates from `QueueStatus` (Queued→Sent), `Routing` ACK/NAK (Sent→Acked/Failed). Per-send ACK timeout via `ackTimeoutJobs`. Fire-and-forget broadcast auto-acks. No mesh-delivery retry on SDK side. |
| Presence tracker | `lastHeartbeatAt: Map<NodeId, Long>` + `offlineNodes: Set<NodeId>`. Marks nodes heard on every inbound frame. Periodic tick scans for `presenceTimeout` expiry, emits `NodeChange.WentOffline`/`CameOnline`. Heartbeats persisted via `DeviceStorage` for survival across process death. |
| Reconnect supervisor | Exponential-backoff reconnect on recoverable transport errors. Configurable via `AutoReconnectConfig` (disabled by default pre-1.0). Resets session state, re-enters Stage 1 handshake. |
| Liveness watchdog | Budget-based watchdog (default 60 s = 2 × heartbeat interval). Reset on every decoded `FromRadio`; decremented on periodic ticks. Budget exhaustion → `TransportError` + teardown. Detects half-open TCP sockets / NAT timeouts. |
| Duplicate detection | Bounded `ArrayDeque` + `Set` of `InboundPacketKey(from, to, channel, id)` with cap of 256 entries. Drop-oldest eviction. |
| Session passkey management | Seeds via `get_owner_request` at handshake end. Persisted to storage. Auto-attached to remote admin packets. TTL-based expiry (4 min). |
| External config change detection | Recognizes unsolicited admin messages (request_id == 0) from the local device and updates `channelsState`/`configBundleState`. Emits `MeshEvent.ExternalConfigChange`. |
| Congestion monitoring | Watches `TELEMETRY_APP` device metrics for air utilization thresholds. Emits `MeshEvent.CongestionWarning` on level transitions. |

### 4.3 Anchor invariants (encoded as tests in `core/src/commonTest/.../invariants/`)

These are the protocol behaviors any working Meshtastic client must honor. They gate every engine refactor.

1. **FIFO frame order.** Single-writer pipe to transport. Outbound frames never reorder under concurrent sends.
2. **Transport-up ≠ session-ready.** `ConnectionState.Connected` is reached *only* after `config_complete_id` matches the session nonce. Until then: `Connecting` or `Configuring`.
3. **Pre-handshake bytes are discarded.** Any `FromRadio` received between transport-connect and the matching `config_complete_id` for the *current* nonce is dropped from the public surface (may be logged). Applies to all transports; fixes v1's BLE-only "drain" rule.
4. **Storage activated before transport.connect().** Keyed by `TransportIdentity`. `recordOwnNode` happens after handshake; storage is never re-keyed on the fly. **If `recordOwnNode` reports a different `NodeNum` than the prior call for the same identity (factory reset, device swap, hostname now points elsewhere), `DeviceStorage` MUST `clear()` itself before persisting the new tuple** — otherwise stale NodeDB rows from the previous radio would leak into the new session. Engine then rebuilds `MeshState` from the fresh handshake payload.
5. **Handshake is an explicit FSM**, not pattern-matched ad hoc.
6. **Admin requests are idempotent and `request_id`-correlated.** Per-op timeouts (default: 30s reads, 60s writes). Never wait forever.
7. **Mesh delivery retries belong to the device.** The SDK never re-enqueues a packet after `Sent` based on its own timer; it waits for the device's `Routing` outcome.
8. **Heartbeat MUST be sent on TCP and serial transports** as `ToRadio(heartbeat = Heartbeat(nonce = 0))` every 30 s. BLE is optional — the SDK ships heartbeat-on-BLE on by default; power-sensitive apps can opt out via `Builder.disableBleHeartbeat()`. Keep-alive heartbeats use `nonce = 0` (firmware interprets `nonce == 1` as "broadcast our nodeinfo over LoRa"; nonces > 0 are reserved). See `docs/protocol.md` §16.
9. **Deferred decrypt is bounded.** Buffer overflow drops oldest; never grows unbounded.
10. **`PayloadTooLarge` is enforced client-side at enqueue** (`DATA_PAYLOAD_LEN = 233`) and surfaces as `MeshtasticException.PayloadTooLarge` thrown from `send()`. It is therefore *not* a `SendFailure` value and never appears in `MessageHandle.state`. The device's `Routing.Error.TOO_LARGE` (should it ever escape pre-validation due to a firmware schema bump) is mapped to `SendFailure.Other(routingError = TOO_LARGE)`.
11. **Liveness watchdog.** After reaching Ready, the engine arms a budget-based watchdog (default 60 s = `HEARTBEAT_INTERVAL_MS * 2`). Every decoded `FromRadio` resets the budget; a periodic tick decrements it. Budget exhaustion tears down the session with a `TransportError("liveness timeout")` — detects half-open TCP sockets and NAT timeouts that `transport.state` may not surface.
12. **Auto-reconnect is opt-in (pre-1.0).** When enabled via `AutoReconnectConfig`, the engine catches recoverable `TransportState.Error`s with exponential backoff + jitter. Session state (pending sends, dedup ring, passkey) is reset between cycles; storage and the supervisor stay live. `ConnectionState.Reconnecting(cause, attempt)` is emitted throughout.
13. **Inbound packet dedup.** The engine maintains a bounded `(from, to, channel, id)` set (cap 256, drop-oldest eviction) and silently drops duplicate decoded packets. Firmware mesh relays can echo packets; the SDK deduplicates so callers never observe the same logical packet twice.
14. **Identity rebind detection.** If `MyNodeInfo.my_node_num` differs from the previously-persisted value for the same `TransportIdentity`, the engine emits `MeshEvent.IdentityRebound` *before* clearing storage, then calls `DeviceStorage.recordOwnNode` (which atomically clears + re-persists). Hosts can observe this on `events` to audit factory resets or radio swaps.
15. **Storage degradation is fail-open.** If any storage write fails during a session, the engine flips a `storageDegraded` flag, emits `MeshEvent.StorageDegraded` **at most once per connect cycle**, and skips all subsequent writes. In-memory state (flows, node DB, packets) continues uninterrupted. The flag resets on the next `connect()`.
16. **Telemetry → node merge.** Inbound `TELEMETRY_APP` packets with `device_metrics` are merged into the node DB and emit `NodeChange.Updated` with `NodeField.Telemetry` / `NodeField.Battery`, so node-list subscribers see battery changes without separate `TelemetryApi` subscription.
17. **External config change detection.** Unsolicited admin messages from firmware (request_id == 0, from == myNodeNum) are detected and used to update `channelsState` / `configBundleState` in-memory + storage. `MeshEvent.ExternalConfigChange` is emitted so hosts can react.

### 4.4 Concurrency model summary

- **One engine coroutine** owns all mutable state; uses `Channel<EngineMessage>(capacity = UNLIMITED)` as inbox.
- **One frame-reader coroutine** per active transport; collects `transport.frames()` and posts `EngineMessage.FrameRx` to the inbox. Gates on `TransportState.Connected` before first collect.
- **One outbound-writer coroutine** drains `Channel<Frame>(UNLIMITED)` and calls `transport.send(frame)` sequentially.
- **One transport-observer coroutine** collects `transport.state` and posts `EngineMessage.TransportStateChanged` to the inbox for disconnect/error detection.
- **Timer coroutines** (heartbeat, liveness, presence check, reconnect backoff) post tick messages into the inbox; all mutations happen on the engine coroutine — timers never mutate state directly.
- Public `Flow`s are backed by engine-owned writable shared state. Per-flow buffering policy:
  - `nodes: Flow<NodeChange>` — replay snapshot once on subscribe, then `extraBufferCapacity = 256` with `SUSPEND` overflow (deltas MUST NOT drop; the snapshot is the consistency anchor).
  - `packets: Flow<MeshPacket>` — `extraBufferCapacity = 128` with `SUSPEND` overflow (chat/text loss is unacceptable). Slow consumers backpressure the engine; if the engine inbox fills, the engine emits `MeshEvent.PacketsDropped(Packets, count)` and drops the *oldest engine-side queued frame* rather than blocking the transport reader. This converts silent loss into an observable event.
  - `events: Flow<MeshEvent>` — `extraBufferCapacity = 64`, `DROP_OLDEST` (events are advisory; never block the engine on observability). Drop bursts surface as `PacketsDropped(Events, n)` on the next event after pressure clears.
  - `connectionState`, `ownNode`, `configBundleState`, `channelsState` — `MutableStateFlow` (conflate, never drop).
- Cancellation: `client.disconnect()` cancels the engine `CoroutineScope`; the actor's `finally` flushes storage and closes the transport.

---

## 5. Tooling

### 5.1 Adopt at Phase 0

| Tool | Purpose |
|---|---|
> **Toolchain is locked to MQTTastic-Client-KMP's stack verbatim** — every plugin, version, and config file is forked rather than re-derived. The MQTTastic build is the canonical org reference; divergence requires an ADR.

| Tool | Purpose |
|---|---|
| Gradle 9.x + Kotlin DSL + version catalog (`gradle/libs.versions.toml`) | Build (Gradle 9.4.1) |
| Convention plugins in `build-logic/convention/` | `meshtastic.kmp.library`, `meshtastic.android.library`, `meshtastic.publishing`, `meshtastic.proto`, `meshtastic.ios.framework`, `meshtastic.sample.jvm`, `meshtastic.sample.android` |
| Kotlin `explicitApi("strict")` on all `:sdk-*` modules **except `:proto`** (Wire-generated; modifier doesn't apply meaningfully to codegen) |
| `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` | New Android KMP library plugin — what MQTTastic uses |
| Spotless + ktlint + `licenseHeaderFile` | Formatting + GPL-3.0 header enforcement (copy `config/spotless/copyright.kt` from MQTTastic) |
| Detekt (config from MQTTastic `config/detekt/detekt.yml`) | Static analysis |
| Kover with `verify { rule { minBound(80) } }` | Coverage gate (matches MQTTastic) |
| Architecture-rule framework | MQTTastic ships a dedicated arch-test library from day 1; we evaluated and chose detekt `ForbiddenImport` + `:core:verifyModuleBoundary` instead — see [ADR-008](decisions/008-architecture-enforcement.md) |
| Kotlin built-in ABI validation (Kotlin 2.2+, `checkKotlinAbi` / `updateKotlinAbi`) | ABI snapshots in `api/` (per-module `<m>.klib.api` + `api/jvm/<m>.api`); **CI gate from Phase 0**. Replaces standalone kotlinx-binary-compatibility-validator. |
| Dokka 2.x with `Module.md` + package overviews | KDoc → HTML; published to gh-pages; coverage gate from Phase 5 |
| Vanniktech `maven-publish-plugin` | Maven Central direct publish + signing (MQTTastic uses `publishToMavenCentral()` — no Sonatype OSSRH dance) |
| `axion-release-plugin` | Git-tag-driven SemVer (MQTTastic uses `gradle.properties` GROUP/VERSION_NAME — we keep axion since we plan more frequent releases) |
| Develocity build cache + foojay toolchain resolver | Match MQTTastic |
| Power-assert plugin (test only) | Pretty intermediate values |
| GitHub Actions | Matrix: `ubuntu-24.04` (android+jvm+native-linux), `macos-latest` (iOS+macOS), `windows-latest` (mingw — only if we add native targets) |
| Renovate | Dependency updates |
| AGENTS.md (single source) + CLAUDE.md / `.github/copilot-instructions.md` redirects | Match MQTTastic agent-instructions pattern |
| **SKIE** (`co.touchlab.skie`) | iOS Swift bridging — exposes Kotlin sealed classes as Swift enums, `Flow` as `AsyncSequence`, suspend functions as `async throws`. Pinned at Phase 0 from a single version in `libs.versions.toml`. See [ADR-007](./decisions/007-ios-distribution.md) for the full iOS-distribution chain (KMMBridge + SKIE + sibling SPM repo). |
| **KMMBridge** (`co.touchlab.kmmbridge`) | Builds, versions, and publishes the iOS XCFramework + SPM Package; the `release.yml` workflow drives it after `publishAndReleaseToMavenCentral`. |

### 5.2 Test stack

- `kotlin.test` + Kotest assertions
- Turbine (Flow testing)
- MockK (JVM-side mocking)
- Power-assert plugin (test only) — pretty intermediate values
- Kotest property module (for `WireCodec`, send tracking, handshake FSM)

### 5.3 Adopt only when justified

Module Graph Assert (graph complexity grows), Dependency Analysis Plugin (leaky `api` exports), kotlinx-benchmark (perf regressions on hot path). (We previously listed an arch-test framework here; superseded by detekt + Gradle per [ADR-008](decisions/008-architecture-enforcement.md).)

### 5.4 Reject

- ~~`kotlinx-io` — Okio is sufficient; don't ship two IO abstractions~~ **REVERSED:** MQTTastic-Client-KMP uses `kotlinx-io-bytestring` for payloads. Align — use `kotlinx-io` for bytes/buffers in commonMain to match the org's house style. Okio remains acceptable for transport-internal IO where it's already idiomatic (Ktor sockets), but public payload types are `kotlinx.io.bytestring.ByteString`.
- In-memory `StorageProvider` as default — violates §4.3 invariant 4
- `kotlin.Result<T>` in any public API — Swift doesn't bridge it

---

## 6. Phases

**End-of-phase protocol:** tag a snapshot/release on Sonatype + summary to user + **wait for "go"**.

### Phase −1 — Governance (resolved)

The Meshtastic org already publishes official KMP libraries under `org.meshtastic` (see [`MQTTastic-Client-KMP`](https://github.com/meshtastic/MQTTastic-Client-KMP), `org.meshtastic:mqtt-client:0.1.0`, GPL-3.0, shipped 2026-04-16). This SDK is built by the same maintainers, under the same org, with the same toolchain. No external sign-off needed.

- [ ] Document the charter, scope boundary vs `mqtt-client`, and naming alignment in `docs/decisions/000-charter.md`.
- [ ] File issue on `MQTTastic-Client-KMP` cross-linking this SDK so consumers see both libraries together.

### Phase 0 — Repo bootstrap (scaffold from MQTTastic-Client-KMP)

**Strategy:** clone MQTTastic-Client-KMP and strip it down rather than greenfield from scratch. Every config decision has been made already; reuse them.

- [ ] Create repo. GPL-3.0 LICENSE. README (mission + drop-in story + bootstrap). CONTRIBUTING.md (DCO sign-off). PR template.
- [ ] **Fork from MQTTastic-Client-KMP** (copy, don't submodule):
  - `gradle/libs.versions.toml` — start from MQTTastic's (Kotlin 2.3.20+, Ktor, kotlinx-io-bytestring, BCV, Spotless, Detekt, Kover, Dokka, vanniktech-publish, foojay, Develocity)
  - `gradle.properties` (toolchain, parallel, configuration cache, Develocity)
  - `config/spotless/copyright.kt` + `copyright.kts` (update copyright header to `Meshtastic LLC` 2026)
  - `config/detekt/detekt.yml`
  - `.github/workflows/` (CI matrix, publish, dokka)
  - `AGENTS.md` + `CLAUDE.md` + `.github/copilot-instructions.md` (redirect pattern)
  - `docker/` if relevant for CI integration testing
- [ ] `.gitignore`. `.editorconfig`.
- [ ] Submodule: `proto/src/protobufs` → `meshtastic/protobufs` `main`.
- [ ] Gradle wrapper (9.x). `settings.gradle.kts` with `pluginManagement` + `dependencyResolutionManagement` (copy from MQTTastic, add multi-module includes).
- [ ] Add to version catalog the SDK-specific deps not present in MQTTastic: `wire`, `sqldelight`, `kable`, `usb-serial-for-android`, `jSerialComm`, `mokkery`, `turbine`, `kotest`, `power-assert`, `axion-release`.
- [ ] `build-logic/convention/` plugins: `meshtastic.kmp.library`, `meshtastic.android.library`, `meshtastic.publishing`, `meshtastic.proto`, `meshtastic.ios.framework`, `meshtastic.sample.jvm`, `meshtastic.sample.android`. Library plugin encodes the MQTTastic `applyDefaultHierarchyTemplate()` + custom intermediate-source-set pattern.
- [ ] `core/` module compiles empty for `androidTarget`, `jvm`, `iosArm64`, `iosX64`, `iosSimulatorArm64`. `explicitApi("strict")` on. BCV `updateKotlinAbi` committed.
- [ ] CI matrix builds + `spotlessCheck` + `detekt` + `allTests` + `checkKotlinAbi` + `koverVerify` on Ubuntu (android+jvm) and macOS (iOS).
- [ ] Vanniktech `publishToMavenCentral()` wired; first publish: `org.meshtastic:sdk-core:0.0.1-SNAPSHOT`.
- [ ] Dokka skeleton with `Module.md` (per MQTTastic pattern) publishing to gh-pages on `main`.
- [ ] ADRs: `000-charter.md` (scope vs `mqtt-client`), `001-public-api-uses-generated-protobufs.md` (✅ landed; Wire types are the public data model), `002-architecture.md` (this plan), `003-tooling.md` (locked to MQTTastic stack + delta), `004-licensing.md` (GPL-3.0 + DCO), `005-api-shape.md` (three response shapes; NodeChange deltas), `006-multi-module-rationale.md` (why we split where MQTTastic doesn't).
- [ ] Copy v1 plan §8 verbatim to `docs/protocol.md`.

**Exit:** Empty repo green on all targets; Dokka site live; first SNAPSHOT published as `org.meshtastic:sdk-core:0.0.1-SNAPSHOT`; coverage + ABI gates wired from day 1.

### Phase 1 — Codec + engine skeleton

- [ ] `proto/` — Wire 6 generates DTOs into private package from `proto/src/protobufs`. All targets.
- [ ] ~~`proto-raw/` — Re-export generated package as a public escape-hatch artifact.~~ **Dropped per ADR-001:** `:proto` itself is the public artifact; no separate escape-hatch needed.
- [ ] `core/` — Value classes (`NodeId`, `ChannelIndex`, `MessageId`, `TransportIdentity`), sealed `MeshtasticException`, `Frame`, `RadioTransport` interface, `StorageProvider`/`DeviceStorage` interfaces, `LogSink`.
- [ ] `core/` — `WireCodec`: framing encode/decode, resync (per v1 §8.2: scan for `0x94 0xC3`, length ≤ 512, drop garbage). Property tests for round-trip on every `ToRadio`/`FromRadio` variant. **Fuzz tests** on random byte streams to validate resync robustness.
- [ ] `core/` — `MeshEngine` actor skeleton: `Channel<EngineMessage>` inbox, frame-reader and outbound-writer coroutines, lifecycle (`start`/`stop`).
- [ ] `core/` — `RadioClient.Builder.build()` constructs the engine. `connect()`/`disconnect()`/state flows wired (no transport / storage yet — engine works against in-memory test fakes).
- [ ] `testing/` — `FakeRadioTransport` (script-driven), `InMemoryStorage`, packet builders. Used by codec/engine tests.
- [ ] All public API marked `@RequiresOptIn(level = ERROR, message = "Pre-1.0; surface stabilizes in Phase 5.")` until Phase 5.
- [ ] CHANGELOG entry + updateKotlinAbi committed.

**Exit:** Codec + engine skeleton fully tested in isolation. Snapshot `0.0.2-SNAPSHOT`.

### Phase 2 — TCP transport + storage + first vertical

Why TCP first: no pairing, no Android permissions, no mobile OS quirks. Smallest path to "talks to a real radio."

- [ ] `transport-tcp/` — Ktor sockets implementation. Targets: android, jvm, ios.
- [ ] `core/` — Full `HandshakeMachine` (two-stage FSM with documented states; mermaid diagram in `docs/architecture/handshake-fsm.md`). Stage 1 nonce → metadata/configs/moduleConfigs/channels/file_info → `config_complete_id`; 100 ms settle; `Heartbeat(nonce++)`; 100 ms settle; Stage 2 nonce → nodeDB → `config_complete_id`; then `get_owner_request` to seed `session_passkey`. Discards everything pre-match.
- [ ] `core/` — `MeshState` populated from handshake; emits `NodeChange.Snapshot`/`Added`/`Updated`/`Removed`.
- [ ] `core/` — Basic `MessageQueue` and `MessageHandle` with `SendState` lifecycle wired to `QueueStatus` and `Routing` ACK/NAK.
- [ ] `core/` — `CommandDispatcher` for admin RPC with `request_id` correlation and per-op timeout.
- [ ] `core/` — `PersistenceCoordinator` honoring §4.3 invariant 4.
- [ ] `storage-sqldelight/` — Schema (`nodes`, `channels`, `config`, `own_node`). Targets: android, jvm, ios.
- [ ] `samples/cli/` — JVM `main()`: `--host`, `--port`, optional `--message`, optional `--admin reboot|getOwner`. Connects, prints `NodeChange` and inbound `MeshPacket`s (filtering by `decoded.portnum`) for 30s, sends one text if requested. **First demoable artifact.**
- [ ] `core/` — Periodic heartbeat scheduler (every 30 s, monotonic `nonce`). Wired for TCP from this phase; BLE/Serial pick it up automatically in Phases 3–4.

**Exit:** `samples/cli` works end-to-end against a real radio over WiFi/TCP. Snapshot `0.0.3-SNAPSHOT`.

### Phase 3 — BLE + Android + iOS

Dominant adoption path. Validate before any 0.1.0.

- [ ] `transport-ble/` — Kable. Targets: android, jvm, ios. Implements §8.3 GATT layout: subscribe `fromnum`, drain `fromradio` until empty on each notification, write framed `ToRadio` to `toradio`. ATT MTU 512 negotiated; document fallback when 512 isn't granted (continue at granted MTU; framing still works). Kable ships JVM backends for macOS, Windows, and Linux.
- [ ] `samples/android-app/` — Minimal Android app (Compose): permissions flow (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, location pre-31), device picker, connect, list nodes via `NodeChange`, send a text. **Foreground service is the app's responsibility** — sample app implements one as demonstration but it lives in app code, not in the SDK.
- [ ] `samples/ios-app/` — Xcode project consuming the K/N XCFramework built from `core` + `transport-ble` and exported via SKIE (per [ADR-007](./decisions/007-ios-distribution.md)). SwiftUI: connect, list nodes, send text. Validate:
  - throwing `suspend` bridges to Swift `try await` (SKIE handles)
  - `MessageHandle` + `StateFlow<SendState>` is consumable via `AsyncSequence` (SKIE-generated)
  - sealed hierarchies map to Swift enums (SKIE)
  - value classes — if Swift bridging is rough even with SKIE, ship a `core-swift-bridge` artifact with mirror `data class` types **without changing the core API**
- [ ] CHANGELOG entry. updateKotlinAbi.

**Exit:** BLE works on Android and iOS against a real device. Snapshot `0.0.4-SNAPSHOT`.

### Phase 4 — Serial transports

- [ ] `transport-serial/` — usb-serial-for-android on Android (permission flow via `UsbManager.requestPermission(...)` is **app's responsibility** — sample shows how) and jSerialComm on JVM (115200 8N1), unified via `expect/actual`. Targets: android, jvm. Inherits the engine's 30 s heartbeat scheduler (mandatory on serial; see `protocol.md` §16).
- [ ] `samples/desktop/` — Compose Multiplatform desktop app: transport selector (TCP / Serial-JVM), connect, node list, message log, send text.
- [ ] Stress test: 24h soak with serial transport + frequent telemetry; no leaks, no buffer overflow, heartbeat respected.

**Exit:** All MVP transports working. Snapshot `0.0.5-SNAPSHOT`.

### Phase 5 — API hardening + 0.1.0

- [ ] Iterate API based on iOS findings from Phase 3 (typically minor — sealed hierarchy tweaks, value-class shims if needed).
- [ ] Every public symbol KDoc'd. Dokka coverage CI gate added.
- [ ] `updateKotlinAbi` committed; binary-compat-validator now gates PRs.
- [ ] Remove all `@RequiresOptIn` annotations from public APIs.
- [ ] `:testing` module finalized: `FakeRadioClient`, `FakeRadioTransport`, `InMemoryStorage`, packet builders, deterministic clock, FSM-step harness.
- [ ] **SKIE-export validation gate:** CI step asserts the exported Swift surface compiles into `samples/ios-app` without warnings; sealed hierarchies map to Swift enums; `Flow`s map to `AsyncSequence`. A failing diff blocks the release.
- [ ] **KMMBridge publish dry-run** in CI: confirms the SPM repo (`meshtastic/meshtastic-sdk-spm`) update would succeed before tag day.
- [ ] Real-radio conformance suite (`docs/manual-tests.md`) with scripted assertions: handshake completes, send-text round-trip, admin getOwner round-trip, traceRoute, large-mesh nodeDB sync, reconnect after transport drop.
- [ ] CHANGELOG with every pre-1.0 breaking change.
- [ ] Cut **`0.1.0`** to Maven Central via Vanniktech.
- [ ] Announce on Meshtastic community channels (Discord, forum). Coordinate with project leads.

**Exit:** `0.1.0` on Maven Central. Dokka site live. iOS + Android + JVM consumable. **External adoption begins.**

### Phase 6 — Coverage completions

Driven by adopter feedback; pick from:

- [ ] Firmware update via XModem (`FirmwareApi`)
- [ ] `transport-mqtt-proxy` for `MqttClientProxyMessage` (NOT a `RadioTransport`; a side-channel module). **Depends on `org.meshtastic:mqtt-client`** — do not roll our own MQTT client; wire `MqttClient.messagesMatching("msh/2/e/#")` into the proxy adapter.
- [ ] (optional) `transport-mqtt` true `RadioTransport` adapter for apps that want MQTT-only operation (no local radio). Also depends on `org.meshtastic:mqtt-client`.
- [ ] Optional `host-android` foreground service helper *if* multiple adopters re-implement the same thing — but only if user demand surfaces; per §0 we keep core pure
- [ ] Key-verification flows surfaced through `events`
- [ ] Position-reporting helper (with privacy-precision opt-in)
- [ ] Optional Koin / kotlin-inject sugar artifacts

### Phase 7 — Stabilize to 1.0

- [ ] Real-world feedback from adopters (Meshtastic-Android integration target; community apps).
- [ ] API freeze. Final breaking changes documented.
- [ ] Cut **`1.0.0`**. binary-compat-validator hard-gates ABI from here.

### Future (post-1.0, non-breaking adds)

- `rpc/`, `rpc-adapter/`, `transport-rpc/`, `host-rpc-server/` — remote engine for browser/wasm/decoupled-UI use cases. Designed as adapters around the local engine. Snapshot+delta + versioned envelopes (per critique). Doesn't touch local API.
- `wasmJs` target on `core` (RPC client only) + `samples/wasm-browser/`.

---

## 7. Validation

### 7.1 Per-phase
```bash
./gradlew spotlessCheck detekt build allTests checkKotlinAbi
```

### 7.2 CI
- Matrix: `ubuntu-24.04` (android + jvm), `macos-latest` (iOS).
- `spotlessCheck`, `detekt`, `build`, `allTests`, `checkKotlinAbi`, sample `assemble`.
- From Phase 5: Dokka coverage + checkKotlinAbi are hard gates.
- From Phase 3: iOS skeleton compiles via `xcodebuild` on macOS runner.

### 7.3 Real-radio integration
From Phase 2: a dedicated radio either attached to a runner (TCP-over-WiFi nightly) or a manual smoke-test checklist run pre-release. Phase 5 introduces a scripted conformance suite, not just manual smoke tests.

### 7.4 ABI policy
- **Pre-1.0:** breaking allowed; require explicit `updateKotlinAbi` + CHANGELOG.
- **1.0+:** binary-compatibility-validator hard-gates. Additive only.

---

## 8. Locked Defaults (override only with PR justification)

| Decision | Default |
|---|---|
| License | **GPL-3.0** |
| Group ID | `org.meshtastic` |
| Artifact prefix | `sdk-` (e.g. `sdk-core`, `sdk-transport-ble`) |
| Kotlin package root | `org.meshtastic.sdk` |
| Min Android SDK | 26 |
| JVM toolchain | 21 |
| CLA | DCO sign-off only |
| Routine radio failures | Typed sealed (`SendState.Failed`, `AdminResult.*`) — never thrown |
| Fatal/programmer errors | `MeshtasticException` (sealed); thrown |
| `Result<T>` in public API | Forbidden |
| Storage | Required at builder; **no in-memory default** |
| Logger | `LogSink.Silent` default |
| Engine targets | android, jvm, ios{Arm64, X64, SimulatorArm64} |
| MVP scope | BLE + TCP + Serial(Android/JVM) + SQLDelight storage |
| Wasm / remote-RPC / MQTT / hosts | **Deferred to post-1.0**, designed as additive |
| Concurrency model | Single-actor engine (one coroutine, `Channel` inbox) |
| Node observation | `Flow<NodeChange>` deltas (Snapshot first to new subscribers) |
| Mesh-delivery retry | Device's job — SDK never retries on its own timer |
| Convention plugins | `meshtastic.kmp.library`, `meshtastic.android.library`, `meshtastic.publishing`, `meshtastic.proto`, `meshtastic.ios.framework` |
| Build scaffold source | Forked verbatim from `meshtastic/MQTTastic-Client-KMP` |
| Pre-1.0 ABI | Breaking allowed with `updateKotlinAbi` + CHANGELOG |
| 1.0+ ABI | Hard-gated |

---

## 9. References

### 9.1 Authoritative protocol
- [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs) — schema (vendored)
- [`meshtastic/firmware`](https://github.com/meshtastic/firmware) — behavior reference
- [Meshtastic dev docs — Client API](https://meshtastic.org/docs/development/device/client-api/)
- [Meshtastic dev docs — Mesh Algorithm](https://meshtastic.org/docs/overview/mesh-algo/)
- [Meshtastic dev docs — Encryption](https://meshtastic.org/docs/overview/encryption/)
- [Meshtastic dev docs — MQTT](https://meshtastic.org/docs/software/integrations/mqtt/)

### 9.2 Library / pattern references (study, do not copy)
- **[`meshtastic/MQTTastic-Client-KMP`](https://github.com/meshtastic/MQTTastic-Client-KMP) — SCAFFOLD SOURCE.** Same org, same toolchain, GPL-3.0. Build/CI/agent-instruction conventions are forked verbatim. API patterns (`internal MqttTransport` interface, `StateFlow<ConnectionState>`, `SharedFlow<Message>`, suspend+Flow public surface, DSL builder, `MqttLogger` interface, `Internal-by-Default` rule) are validated precedent for our equivalent shapes.**
- [Coil 3](https://github.com/coil-kt/coil) — KMP capability-based modules
- [Ktor](https://github.com/ktorio/ktor) — KMP plugin architecture
- [Kable](https://github.com/JuulLabs/kable) — KMP BLE
- [SQLDelight](https://github.com/sqldelight/sqldelight) — KMP storage
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — Android USB serial
- [jSerialComm](https://github.com/Fazecast/jSerialComm) — JVM serial

### 9.3 Tooling
- [Vanniktech maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
- [axion-release-plugin](https://github.com/allegro/axion-release-plugin)
- [kotlinx-binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
- [Dokka](https://github.com/Kotlin/dokka)

### 9.4 Reference Android implementation (READ-ONLY, DO NOT COPY)
- [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android) — primary port source for the engine (handshake FSM, codec, BLE/serial dispatch, channel encryption, packet repository).

---

## 10. Phase Completion Protocol

After each phase:
1. Tag (`git tag v0.X.Y && git push --tags`).
2. CI publishes artifact + Dokka.
3. Update CHANGELOG.
4. Summarize to user: *"Phase N done. Shipped: …. Next phase: …. Open questions: …."*
5. **Wait for "go".**

---

## 11. What Changed From v1 (for reviewers)

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
| `kotlinx-io` rejected | `kotlinx-io-bytestring` adopted for payloads | Match MQTTastic house style |
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

---

## Appendix A — Convention plugin sketch

`build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt`:

```kotlin
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("org.jetbrains.dokka")
            apply("org.jetbrains.kotlin.plugin.power-assert")
        }

        extensions.configure<DokkaExtension> {
            moduleName.set(project.name)
            dokkaSourceSets.configureEach {
                includes.from("Module.md")
                sourceLink { /* GitHub source links */ }
            }
        }

        extensions.configure<PowerAssertGradleExtension> {
            functions.set(listOf(
                "kotlin.assert", "kotlin.require", "kotlin.check",
                "kotlin.test.assertTrue", "kotlin.test.assertFalse",
                "kotlin.test.assertEquals", "kotlin.test.assertNotEquals",
                "kotlin.test.assertNull", "kotlin.test.assertNotNull",
            ))
            includedSourceSets.set(listOf(
                "commonTest", "jvmTest", "jvmAndroidTest", "iosTest",
                "iosArm64Test", "iosX64Test", "iosSimulatorArm64Test",
                "appleTest", "androidUnitTest", "androidInstrumentedTest",
            ))
        }

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(libs.findVersion("javaVersion").get().requiredVersion.toInt())    // 21
            explicitApi()
            jvm(); iosArm64(); iosX64(); iosSimulatorArm64()
            applyDefaultHierarchyTemplate()

            sourceSets.apply {
                commonMain.dependencies {
                    implementation(libs.findLibrary("coroutinesCore").get())
                    implementation(libs.findLibrary("kotlinxDatetime").get())
                    implementation(libs.findLibrary("kermit").get())
                }
                commonTest.dependencies {
                    implementation(libs.findLibrary("kotlinTest").get())
                    implementation(libs.findLibrary("turbine").get())
                    implementation(libs.findLibrary("kotestAssertions").get())
                    implementation(libs.findLibrary("coroutinesTest").get())
                }
            }

            targets.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions { allWarningsAsErrors.set(true) }
                    }
                }
            }

            // -Xjvm-expose-boxed: non-mangled boxed accessors for value classes (Java interop)
            targets.matching { it.platformType.name in listOf("jvm", "androidJvm") }.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions { freeCompilerArgs.add("-Xjvm-expose-boxed") }
                    }
                }
            }
        }
    }
}
```

Note: Android library configuration (`namespace`, `compileSdk`, `minSdk`) is in the separate
`AndroidLibraryConventionPlugin` using the `com.android.kotlin.multiplatform.library` plugin.
Publishing (Vanniktech + Dokka + ABI validation) is in `PublishingConventionPlugin`.

---

## Appendix B — `gradle/libs.versions.toml` (actual, as of v2.2)

```toml
[versions]
kotlin                      = "2.3.21"
agp                         = "9.2.1"
gradle                      = "9.4.1"
javaVersion                 = "21"
androidMinSdk               = "26"
androidCompileSdk           = "36"
androidTargetSdk            = "36"
coroutines                  = "1.11.0"
kotlinxIo                   = "0.9.0"
kotlinxDatetime             = "0.7.1"
kotlinxSerialization        = "1.11.0"
atomicfu                    = "0.32.1"
okio                        = "3.17.0"
wire                        = "6.4.0"
sqldelight                  = "2.3.2"
kable                       = "0.42.0"
ktor                        = "3.5.0"
jSerialComm                 = "2.11.4"
mosaic                      = "0.18.0"
clikt                       = "5.1.0"
composeMultiplatform        = "1.10.3"
kermit                      = "2.1.0"
turbine                     = "1.2.1"
kotest                      = "6.1.11"
mockk                       = "1.14.9"
vanniktechMavenPublish      = "0.36.0"
kmmBridge                   = "1.2.1"
skie                        = "0.10.12"
dokka                       = "2.2.0"
ktlint                      = "1.8.0"
spotless                    = "8.4.0"
detekt                      = "2.0.0-alpha.3"
axionRelease                = "1.21.1"
kover                       = "0.9.8"
```

See `gradle/libs.versions.toml` for the full `[libraries]`, `[plugins]`, and `[bundles]` sections.
