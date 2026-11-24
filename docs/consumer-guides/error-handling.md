# Error handling

What to retry, what to surface to the user, and how to write an exhaustive `when` over each failure type. [error-taxonomy.md](../error-taxonomy.md) is the full catalog and wire mapping; this guide is the practical companion.

## The failure carriers

Per [ADR-005](../decisions/005-api-shape.md), the kind of failure decides its shape:

| Carrier | Used for | Where you handle it |
|---|---|---|
| `throw MeshtasticException` | Programmer errors, transport setup, handshake failure | `try { client.connect() }` and around the send calls |
| `SendState.Failed(SendFailure)` on a `MessageHandle` | Per-send delivery outcomes | `handle.await()` or `handle.state.collect { … }` |
| `AdminResult` | Admin, telemetry, routing, and store-and-forward calls | `when` over the returned value |
| `MeshEvent` on `RadioClient.events` | Session-level warnings: storage degraded, items dropped, identity rebound | One `events.collect { … }` per session |

The public API has no `kotlin.Result<T>`.

## `SendFailure`

`MessageHandle.state` walks `Queued → Sent → Acked | Delivered | Failed(SendFailure)`. Source: [`Result.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Result.kt).

| Variant | Cause | Retry |
|---|---|---|
| `NoRoute` | No path to the destination, or a neighbor refused it (`NO_ROUTE`, `GOT_NAK`). | Later; the topology may change. |
| `MaxRetransmit` | The device used up its retransmissions. | Yes, after a wait. |
| `Timeout` | The device reported a routing timeout. | Yes. |
| `DutyCycleLimit` | The region's duty-cycle limit blocked transmission. | Yes, after seconds to minutes. |
| `RateLimited` | The device refused a send that followed the previous one on its port too closely; firmware allows one text message every two seconds. | Yes, after a few seconds. |
| `QueueRejected(res)` | The device's transmit queue rejected the packet, usually because it's full. | Yes, after backing off; pace bulk sends. |
| `AckTimeout` | No routing ACK within `Builder.sendAckTimeout` (default 30 s). Broadcasts are exempt. | Yes, after checking connectivity. |
| `Disconnected` | The transport dropped before the send completed. | Reconnect, then resend. |
| `HandshakeFailed` | The send was queued before `Connected` and the handshake failed. | Reconnect, then resend. |
| `Cancelled` | `MessageHandle.cancel()` before the packet left the host. | No; the caller asked. |
| `IdCollision` | The packet's `id` matches an in-flight send. | No; fix the caller. |
| `Other(routingError)` | Another `Routing.Error`. | Inspect `routingError`. |
| `Unknown(message)` | An engine anomaly. | Treat as a bug and capture diagnostics. |

`SendFailure.humanMessage()` returns display text for any variant.

### Example: one send with retries

```kotlin
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendOutcome

suspend fun sendWithRetry(client: RadioClient, text: String, maxAttempts: Int = 3) {
    var attempt = 0
    while (true) {
        attempt++
        when (val outcome = client.sendText(text).await()) {
            SendOutcome.Success -> return
            is SendOutcome.Failure -> when (val reason = outcome.reason) {
                SendFailure.MaxRetransmit,
                SendFailure.Timeout,
                SendFailure.AckTimeout,
                SendFailure.DutyCycleLimit,
                SendFailure.RateLimited,
                is SendFailure.QueueRejected,
                -> {
                    if (attempt >= maxAttempts) error("gave up after $attempt attempts: $reason")
                    delay((1L shl attempt).seconds) // 2 s, 4 s, 8 s
                }

                SendFailure.NoRoute -> error("no route to destination")

                SendFailure.Disconnected,
                SendFailure.HandshakeFailed,
                -> error("not connected: $reason")

                SendFailure.Cancelled,
                SendFailure.IdCollision,
                -> return

                is SendFailure.Other -> error("device reported ${reason.routingError}")
                is SendFailure.Unknown -> error("unknown failure: ${reason.message}")
            }
        }
    }
}
```

The inner `when` is exhaustive, so a `SendFailure` variant added in a later release is a compile error at this site. Adding one is a SemVer-major change after 1.0. For a fixed policy without your own loop, `handle.retryWith(RetryPolicy.ExponentialBackoff())` resends and returns the final `SendOutcome`.

## `AdminResult`

```kotlin
when (val result = client.admin.getOwner()) {
    is AdminResult.Success -> showOwner(result.value)
    AdminResult.Timeout, AdminResult.NodeUnreachable -> offerRetry()
    AdminResult.RateLimited -> retryLater()
    AdminResult.Unauthorized -> showError("This node doesn't accept admin from this client.")
    AdminResult.SessionKeyExpired -> offerRetry()   // the engine already retried once
    is AdminResult.Failed -> showError("Not delivered: ${result.routingError}") // NO_ROUTE, MAX_RETRANSMIT, ...
}
```

`getOrNull()`, `getOrThrow()`, `onSuccess {}`, and `onFailure {}` shorten the common cases. `batch { … }` throws `AdminResultException` instead of returning a result.

## `MeshEvent`

`RadioClient.events` carries signals that arrive while the session is alive. Source: [`Node.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt). The full table, including the variants not shown here, is in [error-taxonomy.md](../error-taxonomy.md#meshevent-reference).

| Variant | Meaning | Handling |
|---|---|---|
| `TransportError(error)` | The session dropped; `error` is the typed cause. | With auto-reconnect on, show a banner while `connection` is `Reconnecting`; otherwise offer to reconnect. |
| `DeviceRebooted(reason)` | The radio restarted; the session ended. | With auto-reconnect on the engine reconnects; otherwise connect again. |
| `IdentityRebound(previousNodeNum, newNodeNum, reason)` | The device reports a different NodeNum than the stored one. Fires before storage changes and before the next `NodeChange.Snapshot`. | Snapshot in-memory state if needed; tell the user the radio changed when `reason` is `"identity_mismatch"`. |
| `StorageDegraded(reason)` | A storage write failed; nothing more is saved this session. At most once per session. | Show "session isn't being saved"; the next `connect()` retries storage. |
| `PacketsDropped(flow, count)` | A collector of `packets` or `nodes` fell behind and new items were discarded. | Move work off the collector. |
| `ProtocolWarning(message, details)` | A non-fatal anomaly, such as an undecodable envelope. | Log it. |
| `SecurityWarning.DuplicatedPublicKey`, `SecurityWarning.LowEntropyKey` | Suspect key material. | Surface prominently. |
| `SecurityWarning.PublicKeyMismatch` | A contact offered a different key; the stored key still applies. | Warn that the key changed. |
| `KeyVerification` | One step of the device's key-verification exchange. | Show the number or confirm UI; reply with `AdminApi.keyVerification`. |
| `Notification(notification)` | A `ClientNotification` from the device. | Show it if relevant. |

### Example: collecting events

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.meshtastic.sdk.DroppedFlow
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.RadioClient

fun observe(client: RadioClient, scope: CoroutineScope) {
    scope.launch {
        client.events.collect { event ->
            when (event) {
                is MeshEvent.IdentityRebound ->
                    log("radio changed: ${event.previousNodeNum} -> ${event.newNodeNum} (${event.reason})")
                is MeshEvent.StorageDegraded ->
                    showBanner("Storage error, this session isn't being saved: ${event.reason}")
                is MeshEvent.PacketsDropped -> when (event.flow) {
                    DroppedFlow.Packets -> metrics.increment("packets.dropped", event.count)
                    DroppedFlow.Events -> metrics.increment("events.dropped", event.count)
                    DroppedFlow.Nodes -> metrics.increment("nodes.dropped", event.count)
                }
                is MeshEvent.SecurityWarning -> showBanner("Security warning: $event")
                is MeshEvent.TransportError -> log("transport: ${event.error.message}")
                is MeshEvent.ProtocolWarning -> log("protocol: ${event.message} ${event.details}")
                else -> Unit
            }
        }
    }
}
```

`log`, `showBanner`, and `metrics` stand for your app's own functions. Drop the `else` branch to have new `MeshEvent` variants surface as compile errors when you upgrade; [CHANGELOG.md](../../CHANGELOG.md) lists them.

## `MeshtasticException`

Throws are for failures you can't fix without changing the input or the environment. Catch them around `connect()`:

```kotlin
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioClient

suspend fun connectOrReport(client: RadioClient) {
    try {
        client.connect()
    } catch (e: MeshtasticException.Transport) {
        // Bluetooth, socket, USB, or WebSocket setup failed; e.cause has the underlying error.
        showError("Couldn't reach the radio: ${e.message}")
    } catch (e: MeshtasticException.HandshakeTimeout) {
        showError("The radio didn't finish the handshake (${e.stage}). Try again.")
    } catch (e: MeshtasticException.StorageUnavailable) {
        // Mid-session storage failures arrive as MeshEvent.StorageDegraded instead.
        showError("Local storage isn't available.")
    } catch (e: MeshtasticException.AlreadyConnected) {
        throw e   // a programmer error: fix the caller
    }
}
```

The send calls throw `NotConnected` when the client isn't connected and `PayloadTooLarge` for a payload over 233 bytes. [error-taxonomy.md](../error-taxonomy.md#meshtasticexception-hierarchy) lists every subclass and where it comes from.

## Reconnecting

The engine reconnects for you when built with `Builder.autoReconnect(AutoReconnectConfig())`: after a drop, a failed first attempt, a handshake timeout or a reboot it backs off exponentially with jitter and reports each wait as `ConnectionState.Reconnecting(cause, attempt, retryAt)`; `retryNow()` skips the wait. Cap the attempts with `maxAttempts` and the handshake restarts with `maxHandshakeRecoveries`, and set `stableAfter` so a radio that drops right after connecting keeps backing off. For work before an attempt, such as waiting for a USB permission or opening a fresh serial port, build the client with `transport(identity, provider)`. An error retrying cannot fix ends in `ConnectionState.Failed(cause)`: show it and offer `connect()`. Without auto-reconnect, a recoverable failure leaves the client `Disconnected` and calling `connect()` again is up to you; `FirmwareTooOld` and non-recoverable transport errors still end in `Failed`. A power-saving device that drops its BLE link by design reports `DeviceSleeping` in place of `Reconnecting` or `Disconnected`; show it as sleeping rather than as an error ([api-reference.md, DeviceSleeping](../api-reference.md#devicesleeping)). [api-reference.md, AutoReconnectConfig](../api-reference.md#autoreconnectconfig) has the parameters.

## Decision rules

- Programmer errors (`AlreadyConnected`, `NotConnected`, `PayloadTooLarge`, `IdCollision`): don't retry; fix the caller.
- Setup failures from `connect()` (`Transport`, `HandshakeTimeout`, `StorageUnavailable`): show them and offer a retry rather than retrying silently. With auto-reconnect on, the engine retries transport and handshake failures itself until `Failed`; `StorageUnavailable` is never retried and stays yours to handle.
- Transient send failures (`MaxRetransmit`, `Timeout`, `AckTimeout`, `DutyCycleLimit`, `RateLimited`, `QueueRejected`): retry with backoff and a capped attempt count, and surface only when the budget runs out.
- Connectivity loss (`Disconnected` or `HandshakeFailed` on a send, `TransportError`, `Reconnecting`): wait for the connection, then resend.
- Routing (`NoRoute`, or `AdminResult.Failed` with `NO_ROUTE` / `MAX_RETRANSMIT`): usually topology; let the user retry later or pick another destination. `AdminResult.NodeUnreachable` means the link was lost.
- Persistence (`StorageDegraded`): keep running and warn that nothing is being saved.
- Backpressure (`PacketsDropped`): fix the collector.

## Related

- [error-taxonomy.md](../error-taxonomy.md): the catalog and wire mapping
- [api-reference.md](../api-reference.md): signatures
- [storage.md](../architecture/storage.md): what triggers `StorageDegraded` and `IdentityRebound`
- [CHANGELOG.md](../../CHANGELOG.md): new variants in each release
