# Error taxonomy

> Mapping every observable failure to a Kotlin type. Per [ADR-005](./decisions/005-api-shape.md): three response shapes — throwing-suspend for programmer/transport errors, sealed `AdminResult` for routine RPC outcomes, sealed `MeshEvent` for asynchronous observability.

## Decision matrix: which shape carries which failure?

| Failure source | Carrier | Rationale |
|---|---|---|
| **Programmer error** (call before connect, payload over MTU, missing PSK at send) | `throw MeshtasticException` | Calling code is wrong; retrying without changing inputs is futile. |
| **Transport-level failure** (BLE GATT error, socket closed, USB unplug) | `throw MeshtasticException.Transport` from `connect()`; `MeshEvent.TransportError(...)` while connected | `connect()` callers expect throw; established sessions emit and (if recoverable) reconnect. |
| **Handshake failure** (timeout in any stage, malformed envelope, firmware too old) | `throw MeshtasticException` from `connect()` | Connect fails synchronously. |
| **Async device drop** (heartbeat liveness timeout, transport drop after `Connected`) | `connection: ConnectionState.Reconnecting(cause)` + `MeshEvent.TransportError("liveness timeout")` (engine watchdog, 2 × heartbeat) or `TransportError("TCP read timeout after 65000ms")` (stream-transport backstop) | Already past `connect()`; the right channel is the state flow. The engine watchdog (`MeshEngine.LIVENESS_TIMEOUT_MS`) is the primary detector; TCP adds its own read deadline so the pre-`Ready` window is also covered. |
| **Mesh send outcome** (NAK, no route, max retransmit, duty cycle, send-time disconnect) | `MessageHandle.state -> Failed(SendFailure.X)` | Routine on a flaky mesh; not exceptional. |
| **Admin RPC outcome** (NAK, session-key expired, unauthorised, rate-limited, timeout) | `AdminResult.*` sealed variants | Routine; handlers want exhaustive `when`. |
| **Engine drop of an inbound flow** (subscriber too slow) | `MeshEvent.PacketsDropped(flow, count)` | Observable backpressure; never silent. |
| **Storage failure mid-session** | `MeshEvent.ProtocolWarning(...)` + retry; second failure escalates to `MeshtasticException.StorageUnavailable` and triggers reconnect | Storage outages shouldn't kill an active session if recoverable. |

## `MeshtasticException` hierarchy

Source of truth: [`core/src/commonMain/kotlin/org/meshtastic/sdk/MeshtasticException.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/MeshtasticException.kt).

```
MeshtasticException                     ← sealed
├── Transport(reason, cause?)
├── Protocol(reason)                    ← codec/framing fault
├── StorageUnavailable(cause?)
├── FirmwareTooOld(required, present)
├── NotConnected
├── AlreadyConnected
├── PayloadTooLarge(maxBytes)
└── HandshakeTimeout(stage)
```

Conventions:

- All concrete subclasses have a stable, public constructor signature documented in [`api-reference.md`](./api-reference.md). Adding a new subclass is a SemVer-major change post-1.0 (consumers may exhaustively `when`).
- `cause` chains preserve the underlying `Throwable` (Kable `BluetoothException`, Ktor `IOException`, etc.).
- `Transport.recoverable: Boolean` is **not** a public field — recoverability is determined at the transport layer (`TransportState.Error.recoverable`); the `MeshtasticException.Transport` handed to consumers is informational only.

### When each is thrown

| Subclass | Thrown from | Trigger | Example `message` |
|---|---|---|---|
| `Transport` | `connect()` | Underlying `RadioTransport.connect()` failed (BLE bond, socket refused, USB unavailable). | `"Transport failed: connection refused (host=meshtastic.local:4403)"` |
| `Protocol` | engine internal; surfaced via `connect()` if mid-handshake | Wire codec resync exhausted; unknown envelope tag in handshake. | `"Protocol violation: unknown FromRadio variant (tag=99)"` |
| `StorageUnavailable` | `connect()` (initial activate fails) or as engine cause for `Reconnecting` (mid-session) | `StorageProvider.activate()` threw, or repeated mid-session save failures. | `"Storage unavailable: failed to open Mesh.db at /data/.../databases/Mesh.db"` |
| `FirmwareTooOld` | **RESERVED** — not currently thrown by the SDK | Reserved for a future opt-in capability check against `DeviceMetadata.firmware_version`. Today the SDK is forward-compatible and gates features on proto-field presence (`hasPKC`, `ClientNotification` arms) rather than a hard minimum version. | `"Firmware too old: required >= X.Y.Z, present A.B.C"` (shape only) |
| `NotConnected` | `send()`, `nodeSnapshot()`, all `Admin/Telemetry/Routing` calls | Client not in `Connected` state. | `"Not connected: call connect() first"` |
| `AlreadyConnected` | `connect()` | A second `connect()` while already `Connected` is a programmer error. (Idempotent on `Connected` was rejected — silent no-op hides logic bugs in reconnect-loop code.) | `"Already connected; call disconnect() first"` |
| `PayloadTooLarge` | `send()` | Encoded `MeshPacket` exceeds the device-advertised `max_packet_size` (or the conservative 237-byte default pre-handshake). | `"Payload too large: 312 bytes exceeds maxBytes=237"` |
| `HandshakeTimeout` | `connect()` | Per-stage timeout (Stage 1: 20 s; Stage 2: 60 s; SeedingSession: 10 s). | `"Handshake timed out during stage: Stage2 (60s)"` |


## `SendFailure` (inside `SendState.Failed`)

```kotlin
public sealed interface SendFailure {
    public data object NoRoute : SendFailure                    // Routing.NO_ROUTE
    public data object MaxRetransmit : SendFailure              // Routing.MAX_RETRANSMIT
    public data object Timeout : SendFailure                    // engine timeout (no Routing reply)
    public data object DutyCycleLimit : SendFailure             // Routing.DUTY_CYCLE_LIMIT_REACHED
    public data object Disconnected : SendFailure               // transport dropped mid-send
    public data object Cancelled : SendFailure                  // MessageHandle.cancel() pre-Sent
    public data object IdCollision : SendFailure                // outbound packet id clashed with in-flight
    public data object AckTimeout : SendFailure                 // ACK never observed within Builder.sendTimeout
    public data object HandshakeFailed : SendFailure            // send attempted while handshake unwinding
    public data class QueueRejected(val res: Int) : SendFailure // QueueStatus.res != 0 (and != 35) — firmware transmit queue rejected the packet before transmission
    public data class Other(val routingError: Routing.Error) : SendFailure
    public data class Unknown(val message: String) : SendFailure
}
```

### Wire `Routing.Error` → `SendFailure` mapping

The Wire-generated `Routing.Error` enum (from `meshtastic/protobufs:mesh.proto`) is the source of truth. Mapping is in `MeshEngine.processRoutingAck(...)`:

| `Routing.Error` | `SendFailure` |
|---|---|
| `NONE` | `Acked` (or `Delivered` for broadcast) — not a failure |
| `NO_ROUTE` | `NoRoute` |
| `GOT_NAK` | `NoRoute` (explicit neighbor NAK ≈ no route) |
| `TIMEOUT` | `Timeout` |
| `NO_INTERFACE` | `Other(NO_INTERFACE)` |
| `MAX_RETRANSMIT` | `MaxRetransmit` |
| `NO_CHANNEL` | `Other(NO_CHANNEL)` |
| `TOO_LARGE` | `Other(TOO_LARGE)` (should never occur; pre-validated as exception — surfaces as protocol bug if seen) |
| `NO_RESPONSE` | `Other(NO_RESPONSE)` |
| `DUTY_CYCLE_LIMIT` | `DutyCycleLimit` |
| `BAD_REQUEST` | `Other(BAD_REQUEST)` |
| `NOT_AUTHORIZED` | `Other(NOT_AUTHORIZED)` (admin paths intercept this and raise `AdminResult.Unauthorized` instead) |
| `PKI_FAILED` | `Other(PKI_FAILED)` |
| `PKI_UNKNOWN_PUBKEY` | `Other(PKI_UNKNOWN_PUBKEY)` |
| `ADMIN_BAD_SESSION_KEY` | `Other(ADMIN_BAD_SESSION_KEY)` (admin paths intercept; see below) |
| `ADMIN_PUBLIC_KEY_UNAUTHORIZED` | `Other(ADMIN_PUBLIC_KEY_UNAUTHORIZED)` (admin paths intercept) |
| `RATE_LIMIT_EXCEEDED` | `Other(RATE_LIMIT_EXCEEDED)` (admin paths intercept → `AdminResult.RateLimited`) |
| (any new value the proto schema adds) | `Other(value)` — forward-compatible without a code change |

`SendFailure.Unknown` is reserved for engine-internal anomalies (encoded `MeshPacket` with no decoded payload, etc.) and should never appear in production.

### `QueueStatus` → `SendFailure.QueueRejected` mapping

`QueueStatus`-driven failures are different from `Routing.Error` NAKs: a non-zero `QueueStatus.res` means the **local** device's transmit queue rejected the packet before it was ever transmitted, and the value lives in the firmware's ERRNO namespace, not the `Routing.Error` enum. In that namespace `32` = unknown/queue full, `33` = no interfaces, `34` = radio disabled, and `35` (`ERRNO_SHOULD_RELEASE`) actually means **success** — the SDK treats `res == 35` as `Sent`, not a failure. Values `1..31` overlap with genuine `Routing.Error` codes (e.g. `DUTY_CYCLE_LIMIT` = 9). The SDK surfaces any rejecting `res` (`!= 0` and `!= 35`) as `SendFailure.QueueRejected(res)`. For in-flight **admin RPCs**, ERRNO rejections (`32..34`) map to `AdminResult.NodeUnreachable`, while `1..31` flow through the normal `Routing.Error` → `AdminResult` mapping below.

## `AdminResult.Error`

```kotlin
public sealed interface AdminResult<out T> {
    public data class Success<T>(val value: T) : AdminResult<T>
    public data object SessionKeyExpired : AdminResult<Nothing>      // → automatic 1× retry inside engine
    public data object Unauthorized : AdminResult<Nothing>           // NOT_AUTHORIZED / ADMIN_PUBLIC_KEY_UNAUTHORIZED
    public data object Timeout : AdminResult<Nothing>
    public data object RateLimited : AdminResult<Nothing>            // RATE_LIMIT_EXCEEDED — back off before retry
    public data object NodeUnreachable : AdminResult<Nothing>        // remote-node admin: NO_ROUTE / MAX_RETRANSMIT
    public data class Failed(val routingError: Routing.Error) : AdminResult<Nothing>  // anything else
}
```

### Wire → `AdminResult` mapping

Admin RPC paths intercept `Routing.Error` *before* it would map to a `SendFailure`:

| `Routing.Error` | `AdminResult` |
|---|---|
| `NONE` (response carrying expected payload) | `Success(payload)` |
| `ADMIN_BAD_SESSION_KEY` | `SessionKeyExpired` (engine auto-retries once with refreshed `session_passkey`; if the retry also returns this, the result is forwarded) |
| `NOT_AUTHORIZED`, `ADMIN_PUBLIC_KEY_UNAUTHORIZED` | `Unauthorized` |
| `TIMEOUT` (or engine per-op timeout firing first) | `Timeout` |
| `RATE_LIMIT_EXCEEDED` | `RateLimited` |
| `NO_ROUTE`, `MAX_RETRANSMIT`, `NO_INTERFACE` (for remote-node admin) | `NodeUnreachable` |
| Anything else | `Failed(routingError)` — caller can switch on the raw enum |

### `AdminResult.Timeout` defaults

> **Status: Phase-0 placeholders.** These are starter values, not characterised numbers. They will be calibrated in Phase 2 against (a) real-radio behaviour and (b) the per-op timeouts used in the [`Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android) reference (search for `MeshService` + `HandshakeStateMachine` constants) and the [`Meshtastic-Apple`](https://github.com/meshtastic/Meshtastic-Apple) reference (`Accessory/...` request handlers). The Phase 5 conformance suite will assert the calibrated numbers.

| Operation class | Per-op timeout (placeholder) |
|---|---|
| Local config read/write (`getConfig`/`setConfig`/`getChannel`/`setChannel`/`setOwner`/`setFavorite`/`setIgnored`/`setTime`) | 10 s |
| Local lifecycle (`reboot`/`shutdown`/`factoryReset`/`nodeDbReset`) | 5 s (request only — actual reboot is observed via transport drop) |
| Telemetry `request*` for local node | 10 s |
| Telemetry `request*` for remote node | 60 s |
| `traceRoute` | `2 * hopLimit` seconds (default 14 s) |
| `requestNeighborInfo` for remote | 60 s |
| `editSettings { … }` total | 30 s |

Timeouts are not configurable in 0.x. If consumers need overrides at 1.x.y, that lands additively as `Builder.adminTimeouts(...)` (separate ADR at the time).

## `MeshEvent` reference

| Variant | Trigger | Recommended host action |
|---|---|---|
| `QueueStatusChanged(status)` | `FromRadio.queue_status` arrived | UI free-slot indicator; engine has already updated `MessageHandle`s. |
| `Notification(notification)` | `ClientNotification` (firmware-pushed user-visible event) | Show to user at host's discretion; localisation per `notification.locale`. The engine **also** re-emits security-relevant arms as typed [`SecurityWarning`](#security-warning) variants — callers SHOULD prefer the typed form. |
| `TransportError(error)` | Recoverable transport-layer error encountered while connected | Inform user; engine handles reconnect. |
| `ProtocolWarning(message, details)` | Non-fatal protocol anomaly (skipped malformed envelope, dedup-fault recovery). The optional `details` map carries structured context. | Log; surface to dev tooling only. |
| `IdentityRebound(previousNodeNum, newNodeNum, reason)` | Device reported a different `NodeNum` than the one previously persisted for this transport identity (factory reset, radio swap, hostname re-pointed at a different physical radio). Emitted **before** the SDK clears storage so consumers can snapshot in-memory state if desired. | Optionally surface "your radio was reset" UX; the engine will rebuild `MeshState` from the fresh handshake payload. |
| `DeviceRebooted(reason)` | Device sent `FromRadio.rebooted = true` — the radio restarted mid-session (crash, admin-triggered reboot, firmware update, or brownout). The engine immediately tears the session down: pending sends fail with `HandshakeFailed` if mid-handshake (or `Disconnected` if post-Ready), handshake state resets, and `ConnectionState` transitions to `Disconnected`. | Surface "device restarted" UX if desired; start a fresh `connect()` cycle. |
| `StorageDegraded(cause)` | Storage backend failed and transitioned to read-through-only mode (writes become no-ops). | Surface to dev tooling; engine continues operating with in-memory-only state. |
| `CongestionWarning(freeSlots, totalSlots)` | Device outbound queue is critically full (`free ≤ 1`). | Back off sending; consider showing a "mesh congested" indicator. |
| `ExternalConfigChange(config)` | Unsolicited admin message from firmware changed a config value (e.g., another client or device UI changed a setting). | Refresh any config-dependent UI state. |

### Identity rebind

`MeshEvent.IdentityRebound(previousNodeNum, newNodeNum, reason)` fires
when the connected radio reports a different `NodeNum` than the one
previously persisted for this transport identity. The event is emitted
**before** the engine clears its storage and before the subsequent
fresh `NodeChange.Snapshot` lands on `RadioClient.nodes`, so subscribers
have a single ordered signal they can snapshot in-memory state from.
See [`architecture/storage.md`](./architecture/storage.md#consumer-observable-signal-r-9)
for the rationale and on-disk behaviour. (Resolved audit finding
**S-P0-2** / roadmap R-9; previously this rebind was silent.)
| `KeyVerification(prompt)` | Key-verification flow initiated by `protocol.md` §10 PKI handshake | Show user-facing comparison UI; respond via host-defined affordance (out of MVP). |
| `PacketsDropped(flow, count)` | Subscriber to `packets`/`events` flow could not keep up; engine shed `count` items | Tell user "you missed N messages"; consider increasing host buffering. |
| `SecurityWarning.DuplicatedPublicKey` | Firmware observed another node broadcasting the *same* public key as one already in its NodeDB — cloned device, or a near-field identity-theft attempt. | Surface prominently to the user; pair with a "verify your contacts" UX. |
| `SecurityWarning.LowEntropyKey` | Firmware reports the current private key was generated with insufficient entropy (freshly flashed board before RNG warmed up). | Prompt the user to regenerate keys via admin UI. |

### Security warnings

The two `SecurityWarning` sub-variants arrive from the firmware's
`ClientNotification.payload_variant` oneof (`duplicated_public_key`,
`low_entropy_key` — see `proto:meshtastic/mesh.proto`). The engine emits
the raw `MeshEvent.Notification` **and** the typed variant so consumers
can pattern-match exhaustively on `SecurityWarning`. Upstream audit F-5.2.

## Observability summary

A consumer that wants exhaustive diagnostics observes:

```kotlin
client.connection.collect { state -> /* show connecting/connected/reconnecting */ }
client.events.collect { event -> /* surface warnings, drops, notifications, key prompts */ }
// Per-send:
client.send(p).state.collect { /* Queued -> Sent -> Acked/Delivered/Failed */ }
// Per-admin call:
when (val r = client.admin.setConfig(c)) {
    is AdminResult.Success -> { … }
    is AdminResult.SessionKeyExpired -> { … }   // very rare — engine already retried once
    is AdminResult.Unauthorized,
    AdminResult.Timeout,
    AdminResult.RateLimited,
    AdminResult.NodeUnreachable,
    is AdminResult.Failed -> { … }
}
```

Combined, the three shapes cover every observable failure mode without the consumer ever needing to consult `MeshtasticException`'s subclasses *during* a connected session.

## Related

- [ADR-005](./decisions/005-api-shape.md) — three-shapes rationale
- [`api-reference.md`](./api-reference.md) — full signatures
- [`protocol.md`](./protocol.md) §11 — Routing semantics
- [`protocol.md`](./protocol.md) §13 — admin/session-passkey
