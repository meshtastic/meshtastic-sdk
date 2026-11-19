# Glossary

> Terms used across [`protocol.md`](./protocol.md), the ADRs, and the public API. Where a Meshtastic-specific name and a Kotlin SDK name diverge, both are listed.

## Identifiers

| Term | Definition | Where it lives |
|---|---|---|
| **NodeNum** | Wire-level 32-bit unsigned device identifier. Truncation of the device's 8-byte unique ID; carried in `MeshPacket.from`, `MeshPacket.to`, `NodeInfo.num`. The radio's *primary key* on the mesh. | `protobufs:Mesh.proto` |
| **NodeId** (SDK) | Public typed wrapper: `value class NodeId(val raw: Int)`. Wraps a device's 32-bit node number; type system makes it harder to confuse with raw `Int`. | `:core` `org.meshtastic.sdk.NodeId` |
| **Node ID short form** (UI) | 8-char hex string (`"!a1b2c3d4"`) often shown in clients; computed as `'!' + NodeNum.toString(radix=16).padStart(8, '0')`. Not used on the wire; convenience only. | `NodeId.shortForm` extension |
| **packet_id / PacketId** | 32-bit per-packet identifier set by the originator. Used for ACK/dedup along the mesh. Engine generates per-send via a monotonic + random-bias counter (`protocol.md` §11). | `:core` `org.meshtastic.sdk.PacketId` |
| **request_id / RequestId** | 32-bit identifier on `MeshPacket.decoded` payloads addressed to the phone for request/response correlation. Used by `AdminMessage`, `Routing`, `Position` query, etc. Distinct from `packet_id`. | `:core` `CommandDispatcher` |
| **MessageId** (SDK) | Public typed wrapper for a host-side outbound packet handle. Equal to the `packet_id` allocated for that send. | `MessageHandle.id` |
| **TransportIdentity** | Stable cache key derived from a `TransportSpec`. Upper-cases BLE MAC addresses; lower-cases TCP host and HTTP URL; serial device names echo input. Storage is keyed by this. | ADR-005 |
| **session_passkey** | 8-byte token issued by the device on `get_owner_response`; required on every `AdminMessage` for the rest of the session. Re-issued on every reconnect. | `protocol.md` §13 |

## Channels and crypto

| Term | Definition |
|---|---|
| **Channel** | Named slot 0..7 on the device, with a name, a PSK, and an `uplink/downlink` MQTT flag. Channel 0 is "primary". `protocol.md` §17. |
| **PSK** | Pre-shared symmetric key for a channel. 1, 16, or 32 bytes. 1-byte form is the "shorthand" PSK index. |
| **PSK index / shorthand** | 1-byte PSK encoding firmware-defined: `pskIndex 0` = unencrypted; `pskIndex 1` = unmodified default key; `pskIndex N≥2` = default key with last byte += `(N-1)` (uint8 wrap). The Meshtastic UI exposes this as `simple1..simple9` ↔ pskIndex 2..10. `protocol.md` §9. |
| **channel hash** | 1-byte hash of `(channel name, PSK)`; carried in `MeshPacket.channel` for encrypted packets so the receiver can pick the right channel without trying every key. `protocol.md` §9. |
| **deferred decrypt** | Engine-side bounded ring buffer for `MeshPacket.encrypted` whose channel hash doesn't match any known channel yet. Re-tried on every new `Channel`/`Config.security` arrival. ADR-002. |
| **PKI DM** | Direct message encrypted to a specific node's curve25519 public key, not a channel PSK. Decrypted by the **device**, not the SDK. Phone receives it already-decrypted on `MeshPacket.decoded`. `protocol.md` §10. |

## Wire envelopes

| Term | Definition |
|---|---|
| **PhoneAPI** | The bidirectional `ToRadio`/`FromRadio` stream the device exposes over BLE/TCP/Serial/HTTP. `protocol.md` §5. |
| **`ToRadio`** | Top-level proto sent phone → device: `packet`, `want_config_id`, `disconnect`, `xmodem_packet`, `mqttClientProxyMessage`, `heartbeat`. |
| **`FromRadio`** | Top-level proto sent device → phone: `packet`, `my_info`, `node_info`, `config`, `module_config`, `channel`, `queue_status`, `xmodem_packet`, `metadata`, `mqttClientProxyMessage`, `file_info`, `client_notification`, `device_ui_config`, `config_complete_id`, `rebooted`. |
| **want_config_id** | A 32-bit nonce the phone sends to start the configuration handshake. Specials: `69420` = Stage 1 (config-only), `69421` = Stage 2 (nodes-only). `protocol.md` §6. |
| **`config_complete_id`** | A `FromRadio` envelope echoing back the nonce when the device has sent everything it owes for that stage. Engine uses it to advance the FSM. |
| **MeshPacket** | The unit of mesh delivery. Carries either `decoded: Data` (cleartext) or `encrypted: bytes` + `channel` (one-byte channel hash). `protocol.md` §7. |
| **PortNum** | App-level demultiplexer in `Data.portnum`. `TEXT_MESSAGE_APP=1`, `POSITION_APP=3`, `NODEINFO_APP=4`, `ROUTING_APP=5`, `ADMIN_APP=6`, `TELEMETRY_APP=67`, `MQTTCLIENTPROXY_APP=72`, etc. `protocol.md` §8. |
| **ROUTING_APP** | Special PortNum carrying ACK/NAK/Routing.Error envelopes addressed to the originator's `request_id`. The engine matches these against pending sends and admin requests. |

## Lifecycle and queue

| Term | Definition |
|---|---|
| **Stage 1 / Stage 2 handshake** | Two-pass config exchange: Stage 1 emits configs/channels/file_info; Stage 2 emits NodeDB. See `architecture/handshake-fsm.md`. |
| **Heartbeat** | `Heartbeat { uint32 nonce = 1 }` sent phone → device on a per-transport schedule. Nonce must change every send to defeat firmware memcmp dedup. `protocol.md` §16. |
| **QueueStatus** | `FromRadio.queue_status { uint32 mesh_packet_id; uint32 res; uint32 free; uint32 maxlen }`. Engine watches `mesh_packet_id` to advance `SendState` from `Queued` → `Sent`. `protocol.md` §16. |
| **Routing ACK / NAK** | `MeshPacket.decoded.portnum=ROUTING_APP` with `request_id` matching an outbound packet. ACK transitions `Sent → (Acked | Delivered)`; NAK transitions `→ Failed(reason)`. `protocol.md` §11. |
| **want_response** | Bool on `MeshPacket.decoded`. When true, the receiver MAY reply; combined with `request_id` it's the basis for SDK admin RPCs. |
| **`SendState`** (SDK) | Sealed `Queued → Sent → (Acked | Delivered | Failed(SendFailure))`. `Delivered` requires an end-to-end ACK from the addressed node, not just a router relay. ADR-005. |

## Storage

| Term | Definition |
|---|---|
| **`StorageProvider`** | Top-level pluggable interface; the consumer constructs one and hands it to `Builder.storage(...)`. Hands out `DeviceStorage` per `TransportIdentity`. |
| **`DeviceStorage`** | Per-radio key/value + structured store for NodeDB, channels, configs, session_passkey hint, last-seen timestamps. |
| **identity rebind** | When `recordOwnNode` detects a NodeNum mismatch for an existing identity (factory reset, hostname now points at a different radio). Storage atomically `clear()`s and re-records; engine emits `MeshEvent.ProtocolWarning("identity rebound to new NodeNum")`. ADR-005. |

## Errors and events

| Term | Definition |
|---|---|
| **`MeshtasticException`** | Sealed throwable hierarchy. Used for programmer errors and pre-validation failures (`PayloadTooLarge`, `FirmwareTooOld`, `NotConnected`, transport errors). |
| **`AdminResult<T>`** | Sealed: `Success(T) | SessionKeyExpired | Unauthorized | Timeout | NodeUnreachable | Failed(routingError)`. Used for routine admin RPC outcomes that callers handle, not raise. |
| **`SendFailure`** | Sealed reason inside `SendState.Failed`: `Disconnected | Cancelled | Timeout(after) | Routing(error: Routing.Error) | Other(...)`. **Does NOT include `PayloadTooLarge`** — that's an exception. ADR-005. |
| **`MeshEvent`** | Sealed observability stream: `QueueStatusChanged(status)`, `Notification(notification)`, `TransportError(error)`, `ProtocolWarning(message)`, `KeyVerification(prompt)`, `PacketsDropped(flow, count)`. |

## Transports

| Term | Definition |
|---|---|
| **BLE GATT** | Meshtastic's primary phone-link. Two characteristics: `FROMRADIO` (read+notify), `TORADIO` (write). `protocol.md` §3. |
| **TCP PhoneAPI** | TCP/4403 by default; same `ToRadio`/`FromRadio` framing as BLE/Serial (4-byte length prefix per §2). |
| **Serial PhoneAPI** | UART (USB-CDC on Android, system serial on JVM). Uses the same length-prefixed framing with a magic byte sync. `protocol.md` §2.2. |
| **HTTP PhoneAPI** | REST endpoints `/api/v1/fromradio` (poll) + `/api/v1/toradio` (POST). Higher latency; not the recommended interactive path. `protocol.md` §4. |
| **MQTT proxy mode** | The device speaks MQTT to an external broker by tunnelling through the phone via `MqttClientProxyMessage`. The phone is a transparent relay; the broker conversation is fully owned by the device. `protocol.md` §14. |

## Cross-references

- [`protocol.md`](./protocol.md) — full wire spec
- [`decisions/`](./decisions/) — ADRs with rationale for each design choice
- [`architecture/handshake-fsm.md`](./architecture/handshake-fsm.md), [`architecture/engine-actor.md`](./architecture/engine-actor.md), [`architecture/module-graph.md`](./architecture/module-graph.md) — visual companions
- [`api-reference.md`](./api-reference.md) — public Kotlin signatures
- [`error-taxonomy.md`](./error-taxonomy.md) — error hierarchy + wire mapping
