# Error taxonomy

Every observable failure, mapped to the Kotlin type that carries it. Per [ADR-005](./decisions/005-api-shape.md) there are three shapes: a thrown `MeshtasticException` for programmer and setup errors, a sealed result (`SendFailure`, `AdminResult`) for routine outcomes, and a `MeshEvent` for asynchronous observability.

## Which shape carries which failure

| Failure | Carrier | Why |
|---|---|---|
| Programmer error: send before connect, payload over the limit, second `connect()` | `throw MeshtasticException` | Retrying without changing the input is futile. |
| Transport failure at connect: Bluetooth GATT error, socket refused, USB unavailable, WebSocket refused | `throw MeshtasticException.Transport` from `connect()` | Connect callers expect a throw. |
| Handshake failure: a stage times out, malformed envelope | `throw MeshtasticException` from `connect()` | Connect fails before the session exists. |
| Session drop after `Connected`: transport closed, liveness watchdog, TCP read timeout | `MeshEvent.TransportError(error)`, then `ConnectionState.Reconnecting` when auto-reconnect is on, else `Disconnected` | The caller is past `connect()`; the state flow is the channel. |
| Mesh send outcome: NAK, no route, retransmits exhausted, duty cycle, rate limit, queue rejection, disconnect | `MessageHandle.state` reaches `Failed(SendFailure)` | Routine on a busy mesh. |
| Admin, telemetry, routing, or store-and-forward call outcome | `AdminResult` | Routine; handlers want an exhaustive `when`. |
| File transfer outcome | `FileTransferResult.Failure(FileTransferFailure)` | Routine. |
| Slow collector on `packets` or `nodes` | `MeshEvent.PacketsDropped(flow, count)` | Loss is reported, not silent. |
| Storage write failure mid-session | `MeshEvent.StorageDegraded(reason)`; writes stop for the session | A storage outage doesn't end a live session. |

The session-drop signals come from the engine's liveness watchdog, which fails the session with `Transport("liveness timeout")` after 60 s (two heartbeat intervals) without a decoded `FromRadio`, and from `TcpTransport`, which fails a read with `Transport("TCP read timeout after 65000ms")` so the window before the handshake completes is covered too.

## `MeshtasticException` hierarchy

Source: [`MeshtasticException.kt`](../core/src/commonMain/kotlin/org/meshtastic/sdk/MeshtasticException.kt).

```text
MeshtasticException                     sealed
├── Transport(reason, cause?)
├── Protocol(reason, cause?)
├── HandshakeTimeout(stage)
├── StorageUnavailable(message?, cause?)
├── FirmwareTooOld(required, present)
├── NotConnected
├── AlreadyConnected
└── PayloadTooLarge(maxBytes)
```

- `cause` keeps the underlying `Throwable`: a Kable exception, a Ktor or socket I/O error, a jSerialComm error.
- `transportIdentity` and `operation` attribute a failure without parsing the message. The SDK sets them; callers read them.
- Recoverability isn't on the exception. A transport reports it through `TransportState.Error.recoverable`; for example, `WebSocketTransport` reports close code 4001 (displaced by another client) and 1009 as not recoverable.
- Adding a subclass is a SemVer-major change after 1.0, since consumers may `when` over the hierarchy exhaustively.

### When each is thrown

| Subclass | Thrown from | Trigger | `message` |
|---|---|---|---|
| `Transport` | `connect()`; `RadioTransport.send()`; `bondedBleDevices()`; `AndroidUsbSerialPorts.open()` | The transport couldn't connect or write: Bluetooth bond or GATT failure, socket refused, USB device gone or not permitted, WebSocket handshake refused or interrupted by `disconnect()`. | Set by the transport, for example `"WebSocket connect was interrupted by disconnect()"` |
| `Protocol` | `connect()`; `WireCodec`; `MessageHandle.retry()` | The handshake failed, including `"Device rebooted mid-handshake"`. `WireCodec` couldn't encode a `ToRadio` or decode a `FromRadio`. `retry()` got a handle not produced by `send()`. | Describes the failure |
| `HandshakeTimeout` | `connect()`; `connectAndAwaitReady()` | Stage 1 got no `config_complete_id` within 20 s (with one `want_config_id` retry at 10 s). Stage 2 received no frame for 30 s, or ran past 60 s in total. Seeding the session passkey took over 10 s. `connectAndAwaitReady` timed out. | `"Handshake timed out during stage: Stage 1"`; `stage` is `Stage 1`, `Stage 2`, `Seeding Session`, or `connectAndAwaitReady` |
| `StorageUnavailable` | `connect()` | `StorageProvider.activate()` failed. Mid-session failures surface as `StorageDegraded` instead. | Default `"Storage unavailable"` |
| `FirmwareTooOld` | Not thrown | Reserved. The SDK gates features on proto field presence rather than a minimum firmware version. | `"Firmware requires newer client (need X, have Y)"` |
| `NotConnected` | `send()`, `sendText()`, `sendReaction()`, `send(portnum, …)`, `sendRaw()`, `requestNodeInfo()`, `nodeSnapshot()` | The client isn't `Connected`. The sub-APIs report failure as a result instead. | `"Client not connected"` |
| `AlreadyConnected` | `connect()` | `connect()` while connected. A silent no-op would hide reconnect-loop bugs. | `"Client already connected"` |
| `PayloadTooLarge` | `send()` and the convenience senders | `decoded.payload` exceeds `DATA_PAYLOAD_LEN` (233 bytes). | `"Payload exceeds 233 bytes"` |

## `SendFailure` (inside `SendState.Failed`)

```kotlin
public sealed interface SendFailure {
    public data object NoRoute : SendFailure
    public data object MaxRetransmit : SendFailure
    public data object Timeout : SendFailure
    public data object DutyCycleLimit : SendFailure
    public data object RateLimited : SendFailure
    public data object Disconnected : SendFailure
    public data object HandshakeFailed : SendFailure
    public data object Cancelled : SendFailure
    public data object IdCollision : SendFailure
    public data object AckTimeout : SendFailure
    public data class QueueRejected(val res: Int) : SendFailure
    public data class Other(val routingError: Routing.Error) : SendFailure
    public data class Unknown(val message: String) : SendFailure
}
```

| Variant | Source |
|---|---|
| `NoRoute`, `MaxRetransmit`, `Timeout`, `DutyCycleLimit`, `RateLimited`, `Other` | A routing NAK; see the following table |
| `Disconnected` | The transport dropped, or `disconnect()` ran, before the send completed |
| `HandshakeFailed` | The send was queued before `Connected` and the handshake failed |
| `Cancelled` | `MessageHandle.cancel()` before `Sent` |
| `IdCollision` | The packet's `id` matches an in-flight send; the existing handle is kept |
| `AckTimeout` | A unicast `want_ack` send got no routing ACK within `Builder.sendTimeout` (default 30 s). Broadcasts never produce it. |
| `QueueRejected(res)` | The device's transmit queue rejected the packet; see [`QueueStatus` rejections](#queuestatus-rejections) |
| `Unknown` | Reserved for engine anomalies |

### Wire `Routing.Error` to `SendFailure`

The mapping is in `MeshEngine` and is driven by the Wire-generated `Routing.Error` enum.

| `Routing.Error` | Result |
|---|---|
| `NONE` | `Acked`, or `Delivered` for a broadcast; not a failure |
| `NO_ROUTE`, `GOT_NAK` | `NoRoute` |
| `MAX_RETRANSMIT` | `MaxRetransmit` |
| `TIMEOUT` | `Timeout` |
| `DUTY_CYCLE_LIMIT` | `DutyCycleLimit` |
| `RATE_LIMIT_EXCEEDED` | `RateLimited`. Firmware allows one text message every two seconds. |
| anything else, including values added to the schema later | `Other(value)` |

### `QueueStatus` rejections

A non-zero `QueueStatus.res` means the local device's transmit queue rejected the packet before it was sent. Values from 32 up are in the firmware's ERRNO namespace, not `Routing.Error`: 32 is queue full or unknown, 33 no interface, 34 radio disabled, and 35 (`ERRNO_SHOULD_RELEASE`) means success, so the SDK treats 35 as `Sent`. Values 1 to 31 are genuine `Routing.Error` codes, such as `DUTY_CYCLE_LIMIT` (9).

Any rejecting `res` fails a tracked send with `SendFailure.QueueRejected(res)`. An in-flight RPC fails with `AdminResult.NodeUnreachable` for 32 and up, and through the `Routing.Error` mapping in the next section for 1 to 31.

## `AdminResult`

```kotlin
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

### Wire to `AdminResult` mapping

RPC paths map a `Routing.Error` before it would become a `SendFailure`:

| `Routing.Error` | `AdminResult` |
|---|---|
| `NONE` with the expected payload | `Success(payload)` |
| `ADMIN_BAD_SESSION_KEY` | `SessionKeyExpired`, after one automatic retry with a refreshed passkey |
| `NOT_AUTHORIZED`, `ADMIN_PUBLIC_KEY_UNAUTHORIZED` | `Unauthorized` |
| `RATE_LIMIT_EXCEEDED` | `RateLimited` |
| `TIMEOUT` | `Timeout` |
| anything else, `NO_ROUTE`, `GOT_NAK` and `MAX_RETRANSMIT` included | `Failed(routingError)` |

A remote read whose `MAX_RETRANSMIT` comes from the connected radio keeps waiting for the reply, which can still arrive over another path, and returns `Failed(MAX_RETRANSMIT)` only at its timeout.

Setters that complete on a routing ACK map the resulting `SendFailure` instead: `NoRoute` and `MaxRetransmit` become `Failed` with that error; `Disconnected`, `HandshakeFailed`, `DutyCycleLimit`, `Cancelled`, and `IdCollision` become `NodeUnreachable`; `Timeout` and `AckTimeout` become `Timeout`; `QueueRejected` and `RateLimited` become `RateLimited`; `Other` follows the table. `NodeUnreachable` therefore means the link was lost or the send never left. Over BLE, a restart-class command to the connected node that the transport wrote before the link dropped returns `Success` (see [protocol.md](protocol.md), edit transactions).

`lockdown` on an `AdminApi` from `forNode(…)` returns `Unauthorized`; it works on the local node only. When the local node's `security.is_managed` is set, most admin calls on the local node, `setTime` among them, return `Unauthorized` without sending.

### Timeouts

Every admin, telemetry, routing, and store-and-forward RPC waits up to `Builder.rpcTimeout` (default 30 s) and then returns `Timeout`. A traceroute across many hops can need longer; raise `rpcTimeout` for that client. Admin setters acknowledged by a routing ACK wait the same `rpcTimeout`.

## `MeshEvent` reference

| Variant | Fires when | Host action |
|---|---|---|
| `Notification(notification)` | The device sends a `ClientNotification`. | Show it at your discretion. Security-relevant ones also arrive as `SecurityWarning`. |
| `SecurityWarning.DuplicatedPublicKey` | Firmware saw another node broadcast a public key already in its node database: a cloned node or an impersonation attempt. | Surface it prominently. |
| `SecurityWarning.LowEntropyKey` | Firmware reports the private key was generated with too little entropy. | Prompt the user to regenerate keys. |
| `TransportError(error)` | The session dropped while connected. `error` is the typed cause: `Transport` for link failures and the liveness watchdog, `HandshakeTimeout` or `StorageUnavailable` when those end a session. Match on type, not on `message`. | Inform the user; with auto-reconnect on, watch `ConnectionState.Reconnecting`. |
| `ProtocolWarning(message, details)` | A non-fatal anomaly: an undecodable envelope, a `FromRadio` variant the SDK doesn't surface. `details` carries structured context. | Log it; show it in developer tooling. |
| `IdentityRebound(previousNodeNum, newNodeNum, reason)` | The device reports a different NodeNum than the one stored for this transport identity. See [Identity rebind](#identity-rebind). | Snapshot in-memory state if needed; optionally tell the user the radio changed. |
| `DeviceRebooted(reason)` | The device sent `FromRadio.rebooted`. The engine tears the session down: pending sends fail, and the state goes to `Disconnected`. | Show "device restarted"; connect again, or let auto-reconnect do it. |
| `StorageDegraded(reason)` | A storage write failed. The engine stops writing for the rest of the session and keeps running from memory. At most once per session. | Warn that the session isn't being saved; the next `connect()` retries storage. |
| `CongestionWarning(metrics)` | A node's reported channel or airtime utilization crosses into a different `CongestionLevel`. | Delay non-urgent sends while `metrics.canSendNonUrgent` is false. |
| `ExternalConfigChange(kind)` | Another admin client changed the device's channels, config, or module config; local state is already updated. | Refresh config-dependent UI. |
| `PacketsDropped(flow, count)` | A collector of `packets` (`DroppedFlow.Packets`) or `nodes` (reported as `DroppedFlow.Events`) fell too far behind and new items were discarded. | Move work off the collector. |
| `MqttProxyMessage(message)` | The device tunnels MQTT through the client (`mqtt.proxy_to_client_enabled`). | Publish it to the broker; send broker traffic back with `sendRaw`. |
| `XmodemPacket(packet)` | An XModem frame arrived. `FileTransferApi` consumes these. | Use `client.files` rather than handling it yourself. |
| `FileInfoReceived(info)` | The device announced a file on its filesystem. | Optional file listing. |
| `LockdownStatusChanged(status)` | A hardened (`MESHTASTIC_LOCKDOWN`) build reports its lock state, after `config_complete_id` and after each `AdminApi.lockdown`. It's the only signal that lockdown exists, since it's a build option. | Drive provisioning or unlock UI. |
| `QueueStatusChanged(status)` | A `FromRadio.queue_status` arrives after the handshake: a send result, a heartbeat reply, or a freed slot in a full queue. The engine already applied it to the matching `MessageHandle`. | Optional queue indicator. Under heavy sending these can crowd older events out of the 64-slot buffer for a slow collector. |
| `MqttConnected` | A local `getDeviceConnectionStatus()` response shows the device's own broker connection up, and the previous one on this connection did not. The device never pushes MQTT state, and client-proxied MQTT reads as down. | Refresh MQTT status UI. |
| `MqttDisconnected(reason)` | A local `getDeviceConnectionStatus()` response shows the broker connection down after one on this connection showed it up. `reason` is always null. | Refresh MQTT status UI. |
| `KeyVerification` | Declared but not emitted by the engine. | None. |

### Identity rebind

`IdentityRebound` fires before the engine touches storage and before the next `NodeChange.Snapshot` reaches `nodes`, so subscribers have one ordered point to snapshot state from. `reason` is `"identity_mismatch"` when storage is cleared. It's `"node_renumbered"` when the radio reported the same validated `MyNodeInfo.device_id` under a new NodeNum (firmware 2.8 derives the NodeNum from the public key); storage is kept and only the old own-node row is removed. [storage.md](./architecture/storage.md#identity-rebind-detection-s-p0-2) covers the on-disk behavior.

### Security warnings

The two `SecurityWarning` variants come from the `ClientNotification.payload_variant` oneof (`duplicated_public_key`, `low_entropy_key` in `mesh.proto`). The engine emits the raw `Notification` and the typed variant.

## Putting it together

```kotlin
scope.launch { client.connection.collect { state -> /* connecting, connected, reconnecting */ } }
scope.launch { client.events.collect { event -> /* warnings, drops, notifications */ } }

client.sendText("hello").state.collect { /* Queued, Sent, then Acked, Delivered, or Failed */ }

when (val r = client.admin.setConfig(c)) {
    is AdminResult.Success -> { }
    AdminResult.SessionKeyExpired -> { }   // the engine already retried once
    AdminResult.Unauthorized,
    AdminResult.Timeout,
    AdminResult.RateLimited,
    AdminResult.NodeUnreachable,
    is AdminResult.Failed -> { }
}
```

During a connected session the three shapes cover every failure, so a consumer doesn't need to catch `MeshtasticException` outside `connect()` and the send calls.

## Related

- [ADR-005](./decisions/005-api-shape.md): the three-shapes rationale
- [api-reference.md](./api-reference.md): signatures
- [consumer-guides/error-handling.md](./consumer-guides/error-handling.md): what to retry and what to surface
- [protocol.md §11](./protocol.md#11-routing-ack-retry-semantics): routing semantics
- [protocol.md §13](./protocol.md#13-admin-protocol--session-passkey): admin and the session passkey
