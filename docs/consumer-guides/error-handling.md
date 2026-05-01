# Error handling

> A consumer-side guide to the SDK's failure model: what to retry, what
> to surface to the user, and how to write an exhaustive `when` against
> each sealed type. The canonical catalog of every failure carrier is
> [`docs/error-taxonomy.md`](../error-taxonomy.md) — read that first
> for the design rationale; this guide is the practical companion.

## The three failure carriers

Per [ADR-005](../decisions/005-api-shape.md), the SDK uses **exactly
three response shapes**, picked by the kind of failure being signalled:

| Carrier                                | Used for                                                        | Where you handle it |
|----------------------------------------|-----------------------------------------------------------------|---------------------|
| `throw MeshtasticException`            | Programmer errors, transport setup failure, handshake failure   | `try { client.connect() }` at call sites |
| `MessageHandle.state -> SendState.Failed(SendFailure)` | Per-send delivery outcomes (NAK, no route, ACK timeout, …) | `handle.await()` or `handle.state.collect { … }` |
| `MeshEvent` (collected from `RadioClient.events`) | Asynchronous, session-scoped warnings (storage degraded, packets dropped, identity rebound, …) | a `launch { events.collect { … } }` per session |

There is **no `kotlin.Result<T>`** in the public API; that prohibition
is enforced by the API-shape ADR and an architectural test.

## `SendFailure` — per-send outcomes

`MessageHandle.state` is a `StateFlow<SendState>` that walks
`Queued → Sent → Acked|Delivered|Failed(SendFailure)`. Every variant
below is a terminal `Failed` reason. Source of truth:
[`core/src/commonMain/kotlin/org/meshtastic/sdk/Result.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Result.kt).

| `SendFailure` variant           | Cause                                                                                         | Retry? |
|---------------------------------|-----------------------------------------------------------------------------------------------|--------|
| `NoRoute`                       | Mesh has no path to the destination (`Routing.Error.NO_ROUTE`).                               | Maybe — try later; topology may change. |
| `MaxRetransmit`                 | Device exhausted retransmit budget (`Routing.Error.MAX_RETRANSMIT`).                          | Yes — wait, then resend; the mesh was busy. |
| `Timeout`                       | Admin RPC timed out waiting for a response.                                                   | Yes — usually a transient device hiccup. |
| `DutyCycleLimit`                | Region's duty-cycle ceiling blocked transmission.                                             | Yes, after backoff (seconds–minutes). |
| `Disconnected`                  | Transport went away mid-send.                                                                 | Reconnect, then resend. |
| `HandshakeFailed`               | Sends queued before `Connected` are failed with this when the handshake itself fails.         | Reconnect, then resend. |
| `Cancelled`                     | `MessageHandle.cancel()` was called before the packet left the host queue.                    | No — caller asked. |
| `IdCollision`                   | Caller submitted a packet whose `id` matches an in-flight send (R-P0-1 guard).                | No — fix the caller; the existing handle is preserved. |
| `AckTimeout`                    | Per-send ACK timer expired (default 30 s, see `Builder.sendTimeout`); broadcast packets are exempt. | Yes — but verify connectivity first. |
| `Other(routingError)`           | Any other `Routing.Error` the device reports.                                                 | Inspect `routingError`. |
| `Unknown(message)`              | Should not occur in normal operation.                                                         | Treat as a bug; capture diagnostics. |

### Example: handling a single send

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
        val handle = client.sendText(text)
        when (val outcome = handle.await()) {
            SendOutcome.Success -> return
            is SendOutcome.Failure -> when (val reason = outcome.reason) {
                // Transient — back off and retry.
                SendFailure.MaxRetransmit,
                SendFailure.Timeout,
                SendFailure.AckTimeout,
                SendFailure.DutyCycleLimit -> {
                    if (attempt >= maxAttempts) error("gave up after $attempt: $reason")
                    delay((1L shl attempt).seconds) // 2s, 4s, 8s …
                }

                // Topology may change later — caller decides.
                SendFailure.NoRoute -> error("no route to destination")

                // Connection is gone — caller must reconnect first.
                SendFailure.Disconnected,
                SendFailure.HandshakeFailed -> error("not connected: $reason")

                // Programmer / app bugs — never retry.
                SendFailure.Cancelled,
                SendFailure.IdCollision -> return
                is SendFailure.Other -> error("device reported ${reason.routingError}")
                is SendFailure.Unknown -> error("unknown failure: ${reason.message}")
            }
        }
    }
}
```

> The `when (val reason = …)` is **exhaustive**; adding a new
> `SendFailure` subtype is a SemVer-major change post-1.0. Any new
> branch the SDK adds will surface as a compile error in your code,
> which is the point.

## `MeshEvent` — async warnings and observability

`RadioClient.events` is a `Flow<MeshEvent>`. These are non-fatal,
session-scoped signals — your session is still alive when they arrive.
Source: [`Node.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt).

| `MeshEvent` variant                        | Meaning                                                                                                  | Suggested handling |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------|--------------------|
| `QueueStatusChanged(status)`               | Device's TX queue depth/state changed.                                                                   | Optional UI hint. |
| `Notification(notification)`               | Device emitted a `ClientNotification` (firmware-side toast/log).                                         | Surface to user if relevant. |
| `TransportError(error)`                    | Transport-layer error during an active session (BLE GATT, socket, …).                                   | The state flow will move to `Reconnecting`; show a banner. |
| `ProtocolWarning(message, details)`        | Malformed data, unexpected state, or a recoverable storage retry. May indicate firmware/SDK skew.        | Log; surface only if persistent. |
| `IdentityRebound(prev, new, reason)`       | The connected device reports a different `NodeNum` than what was persisted (factory reset / radio swap). Emitted **before** storage is wiped and the next `NodeChange.Snapshot`. | Snapshot any in-memory state you care about; warn the user that this transport identity now points at a different physical radio. |
| `StorageDegraded(reason)`                  | Persistent storage failed (disk full, locked DB, etc.); engine has dropped to in-memory mode for the rest of the session. Emitted **at most once per session**. | Show "session is not being persisted" banner; reconnect to retry. |
| `KeyVerification(prompt)`                  | Encryption setup wants user confirmation (Phase 1 placeholder).                                          | Show a generic confirm-key UI. |
| `PacketsDropped(flow, count)`              | A consumer-facing flow (`packets` or `events`) overflowed its buffer; oldest items dropped.              | You're collecting too slowly — move work off the collector. |

### Example: collecting events safely

```kotlin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.meshtastic.sdk.DroppedFlow
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.RadioClient

fun observe(client: RadioClient, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        client.events.collect { event ->
            when (event) {
                is MeshEvent.IdentityRebound -> {
                    // Persisted state for previousNodeNum is about to be cleared.
                    // Snapshot anything you care about *now* — the next NodeChange.Snapshot
                    // will reflect the new device.
                    log("radio swap: ${event.previousNodeNum} -> ${event.newNodeNum}")
                    showUserBanner("This transport now points at a different radio.")
                }
                is MeshEvent.StorageDegraded -> {
                    // Engine has stopped writing to disk for the rest of this session.
                    showUserBanner("Storage error — session not being saved (${event.reason}).")
                }
                is MeshEvent.PacketsDropped -> {
                    // The collector for `packets` or `events` is too slow.
                    when (event.flow) {
                        DroppedFlow.Packets -> metrics.increment("packets.dropped", event.count)
                        DroppedFlow.Events  -> metrics.increment("events.dropped",  event.count)
                    }
                }
                is MeshEvent.ProtocolWarning -> log.warn("protocol: ${event.message} ${event.details}")
                is MeshEvent.TransportError  -> log.warn("transport: ${event.error.message}")
                is MeshEvent.QueueStatusChanged,
                is MeshEvent.Notification,
                is MeshEvent.KeyVerification -> { /* optional UI */ }
            }
        }
    }
}
```

> Like `SendFailure`, `MeshEvent` is a sealed interface and consumers
> `when`-ing on it exhaustively will see new variants as compile errors.
> See the [migration section of `CHANGELOG.md`](../../CHANGELOG.md) when
> upgrading.

## `MeshtasticException` — what `connect()` and `send()` throw

Throws are reserved for things you can't recover from without changing
inputs (per ADR-005). Catch them at the call site:

```kotlin
import org.meshtastic.sdk.MeshtasticException

suspend fun connectOrFail(client: RadioClient) {
    try {
        client.connect()
    } catch (e: MeshtasticException.Transport) {
        // Transport setup failed (BLE GATT, socket open, USB enumeration).
        // `e.cause` carries the underlying Kable / Ktor / jSerialComm error.
        showUserError("Couldn't reach the radio: ${e.message}")
    } catch (e: MeshtasticException.HandshakeTimeout) {
        // Stage1/Settling/Stage2 didn't complete in time.
        showUserError("Radio handshake timed out at ${e.stage}; try again.")
    } catch (e: MeshtasticException.FirmwareTooOld) {
        // Device firmware is older than this SDK supports.
        showUserError("Firmware ${e.present} is too old (need ≥ ${e.required}).")
    } catch (e: MeshtasticException.StorageUnavailable) {
        // Storage is unusable from `connect()`. (Mid-session storage failures
        // surface as MeshEvent.StorageDegraded instead.)
        showUserError("Local storage isn't writable.")
    } catch (e: MeshtasticException.AlreadyConnected) {
        // You called connect() twice. Programmer error — fix the caller.
        throw e
    }
}
```

The full hierarchy and "when each is thrown" tables live in
[`docs/error-taxonomy.md`](../error-taxonomy.md).

## Reconnect supervisor (consumer-side)

The engine does not auto-reconnect today (R-8 in [`docs/roadmap.md`](../roadmap.md)). Consumers who need long-lived sessions should observe `RadioClient.connection` and re-issue `connect()` themselves. A minimal, leak-free pattern using exponential backoff:

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioClient
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun CoroutineScope.superviseConnection(client: RadioClient) = launch {
    var backoff = 1.seconds
    val maxBackoff = 60.seconds
    client.connection
        .filterIsInstance<ConnectionState.Disconnected>()
        .collect {
            try {
                client.connect()
                backoff = 1.seconds                                      // reset on success
            } catch (_: MeshtasticException.AlreadyConnected) {
                backoff = 1.seconds                                      // already healed; ignore
            } catch (e: MeshtasticException) {
                // Surface fatal/programmer errors; only retry transients.
                if (e is MeshtasticException.FirmwareTooOld) throw e
                val jittered = backoff + Random.nextLong(0, 250).milliseconds
                delay(jittered)
                backoff = min(backoff.inWholeMilliseconds * 2, maxBackoff.inWholeMilliseconds).milliseconds
            }
        }
}
```

The supervisor only acts on `Disconnected`; intermediate `Reconnecting`/`Connecting`/`Configuring` states are emitted by the engine itself during a connect attempt and must be left alone. See [`protocol.md` §1A](../protocol.md#1a-reconnection-and-backoff) for why the SDK keeps this policy in consumer hands until R-8 lands.

## Decision rules

A short cheat sheet for "do I retry, surface, or both?":

- **Programmer errors** (`AlreadyConnected`, `NotConnected`,
  `PayloadTooLarge`, `IdCollision`) — never retry; fix the caller.
- **Setup failures** (`Transport`, `HandshakeTimeout`, `FirmwareTooOld`,
  `StorageUnavailable` from `connect()`) — surface to user; offer a
  retry button rather than auto-retrying.
- **In-flight transients** (`MaxRetransmit`, `Timeout`, `AckTimeout`,
  `DutyCycleLimit`) — auto-retry with exponential backoff (cap the
  attempt count); only surface after the budget is exhausted.
- **Connectivity loss** (`Disconnected`, `HandshakeFailed` on a send,
  `TransportError` event, `ConnectionState.Reconnecting`) — wait for
  the engine's reconnect, then resend.
- **Mesh routing** (`NoRoute`) — usually a topology issue; surface so
  the user can try a different destination, retry later, or change
  channels.
- **Persistence degradation** (`StorageDegraded`,
  `ProtocolWarning(...)` mentioning storage) — keep running, but warn
  the user that nothing is being saved.
- **Backpressure** (`PacketsDropped`) — fix the collector (move work
  off the flow, or buffer in your own structure).

## Related

- [`docs/error-taxonomy.md`](../error-taxonomy.md) — canonical catalog
  and decision matrix; this guide is its consumer-facing companion.
- [`docs/api-reference.md`](../api-reference.md) — full type
  signatures.
- [`reactive-lifecycle-management.md`](./reactive-lifecycle-management.md)
  — collecting `events` / `nodes` / `connection` without leaks.
- [`docs/architecture/storage.md`](../architecture/storage.md) — what
  triggers `MeshEvent.StorageDegraded` and the identity-rebind contract
  behind `MeshEvent.IdentityRebound`.
- [`CHANGELOG.md`](../../CHANGELOG.md) — migration notes when new
  `SendFailure` / `MeshEvent` variants land.
