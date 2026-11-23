# Glossary

Terms used across [protocol.md](./protocol.md), the ADRs, and the public API. Where a Meshtastic name and an SDK name differ, both are listed.

## Identifiers

| Term | Definition | Where it lives |
|---|---|---|
| NodeNum | The 32-bit unsigned node number on the wire, in `MeshPacket.from`, `MeshPacket.to`, and `NodeInfo.num`. Firmware 2.8 derives it from the node's public key, so a node can renumber. | `mesh.proto` |
| `NodeId` (SDK) | `value class NodeId(val raw: Int)` wrapping a NodeNum. `NodeId.LOCAL` is 0 and `NodeId.BROADCAST` is `0xFFFFFFFF`. | `org.meshtastic.sdk.NodeId` |
| Default node ID | The `!a1b2c3d4` form clients display: `!` plus eight lowercase hex digits. Not used on the wire. | `NodeId.toDefaultId()`, `NodeId.fromDefaultId()` |
| `device_id` | The hardware identifier in `MyNodeInfo`. The SDK uses it to tell a renumbered node from a different radio. | `mesh.proto`; `DeviceStorage.recordOwnNode` |
| Packet ID | The 32-bit per-packet identifier the originator sets in `MeshPacket.id`, used for ACKs and duplicate suppression. The engine allocates one per send. | `protocol.md` §12 |
| `request_id` | The field in `Data` that names the packet a reply answers. Admin, routing, and telemetry replies correlate by it. | `CommandDispatcher` (internal) |
| `MessageId` (SDK) | Wrapper for the packet ID of a send, exposed as `MessageHandle.id`. | `org.meshtastic.sdk.MessageId` |
| `NodeLastByte` (SDK) | The last byte of a NodeNum, as carried in `relay_node` and `next_hop`. Several nodes can share one. | `org.meshtastic.sdk.NodeLastByte` |
| `TransportIdentity` | The storage key for a link, derived from a `TransportSpec` or built by a transport. It uppercases a Bluetooth address and lowercases a TCP host or HTTP URL; serial names stay as given. | ADR-005 |
| `TransportTarget` | A parsed `ble:`, `tcp:`, or `serial:` connection string. Distinct from `TransportSpec`, which only derives an identity. | `org.meshtastic.sdk.TransportTarget` |
| Session passkey | The token a node puts in admin responses; admin messages to that node must carry it. It lasts at least 150 s from issue and certainly expires after 300 s. | `protocol.md` §13; `AdminSessionStatus` |

## Channels and crypto

| Term | Definition |
|---|---|
| Channel | A named, encrypted group that messages are sent to; the device has slots 0 to 7, and slot 0 is the primary channel. `protocol.md` §17. |
| Pre-shared key (PSK) | The symmetric key that encrypts a channel: 0, 1, 16, or 32 bytes. The 1-byte form is a shorthand index. |
| PSK shorthand | A 1-byte PSK: 0 means unencrypted, 1 the default key, and N from 2 up the default key with its last byte increased by N - 1. `protocol.md` §9. |
| Channel hash | A 1-byte hash of the channel name and PSK, carried in `MeshPacket.channel` on encrypted packets so the receiver can pick the key. `protocol.md` §9. |
| Deferred decrypt | The engine's bounded buffer of encrypted packets whose channel hash matches no known channel yet, retried when channels change. ADR-002. |
| PKI direct message | A direct message encrypted to the recipient's X25519 public key instead of a channel PSK. The node encrypts and decrypts it; the client sees it decoded. `protocol.md` §10. |

## Wire envelopes

| Term | Definition |
|---|---|
| Phone API | The `ToRadio` and `FromRadio` exchange a node offers over Bluetooth, TCP, serial, HTTP, and WebSocket. `protocol.md` §5. |
| `ToRadio` | The envelope a client sends: `packet`, `want_config_id`, `disconnect`, `xmodemPacket`, `mqttClientProxyMessage`, `heartbeat`. |
| `FromRadio` | The envelope a node sends: `packet`, `my_info`, `node_info`, `config`, `moduleConfig`, `channel`, `queueStatus`, `xmodemPacket`, `metadata`, `mqttClientProxyMessage`, `fileInfo`, `clientNotification`, `deviceuiConfig`, `log_record`, `lockdown_status`, `config_complete_id`, `rebooted`, and others. `RadioClient.fromRadio` carries every one the SDK decodes. |
| `want_config_id` | The nonce a client sends to start the handshake: 69420 requests Stage 1 (config), 69421 Stage 2 (node database). `protocol.md` §6. |
| `config_complete_id` | The `FromRadio` that echoes the nonce once the node has sent everything for that stage. |
| `MeshPacket` | The unit of mesh delivery: `decoded` (`Data`, cleartext) or `encrypted` bytes plus a channel hash. `protocol.md` §7. |
| `PortNum` | The application selector in `Data.portnum`, such as `TEXT_MESSAGE_APP` (1), `POSITION_APP` (3), `NODEINFO_APP` (4), `ROUTING_APP` (5), `ADMIN_APP` (6), and `TELEMETRY_APP` (67). `protocol.md` §8. |
| `ROUTING_APP` | The port carrying ACKs, NAKs, and `Routing.Error` codes for a packet. The engine matches them to pending sends and RPCs. |
| `log_record` | A device log line sent as a `FromRadio` when `security.debug_log_api_enabled` is set. Bluetooth also offers it on the `logradio` characteristic. |

## Lifecycle and queue

| Term | Definition |
|---|---|
| Stage 1 and Stage 2 | The two handshake passes: Stage 1 sends config, channels, and the local node; Stage 2 sends the node database. `Builder.skipNodeDb()` stops after Stage 1. [handshake-fsm.md](./architecture/handshake-fsm.md). |
| Heartbeat | `ToRadio.heartbeat` with a nonce, sent every 30 s. The nonce changes on every send so firmware doesn't discard it as a duplicate; it counts up from 2. `protocol.md` §16. |
| Liveness watchdog | The engine ends a session after 60 s without a decoded `FromRadio`, once the firmware has answered a heartbeat. |
| `QueueStatus` | `FromRadio.queueStatus` (`res`, `free`, `maxlen`, `mesh_packet_id`). Acceptance moves a send from `Queued` to `Sent`; a rejection fails it with `QueueRejected`. `protocol.md` §16. |
| Routing ACK and NAK | A `ROUTING_APP` reply whose `request_id` names a sent packet. An ACK moves `Sent` to `Acked` (unicast) or `Delivered` (broadcast, when a relay is heard); a NAK fails it. `protocol.md` §11. |
| `want_response` | The flag in `Data` asking the recipient to reply; with `request_id` it's the basis of SDK RPCs. |
| `SendState` (SDK) | `Queued`, `Sent`, then `Acked`, `Delivered`, or `Failed(SendFailure)`. |

## Storage

| Term | Definition |
|---|---|
| `StorageProvider` | The pluggable storage factory passed to `Builder.storage(...)`. Returns a `DeviceStorage` per `TransportIdentity`. |
| `DeviceStorage` | Per-radio storage of the node database, channels, config, session passkey, and heartbeat timestamps. |
| Identity rebind | `recordOwnNode` sees a NodeNum different from the stored one: a factory reset or another radio clears storage, and a renumbered node with the same `device_id` keeps it. The engine emits `MeshEvent.IdentityRebound`. [storage.md](./architecture/storage.md#identity-rebind-detection-s-p0-2). |
| Degraded storage | After a storage write fails, the engine stops writing for the session and emits `MeshEvent.StorageDegraded`. |

## Errors and events

| Term | Definition |
|---|---|
| `MeshtasticException` | Sealed exception hierarchy for programmer and setup errors: `Transport`, `Protocol`, `HandshakeTimeout`, `StorageUnavailable`, `FirmwareTooOld`, `NotConnected`, `AlreadyConnected`, `PayloadTooLarge`. |
| `AdminResult<T>` | Sealed RPC outcome: `Success(value)`, `SessionKeyExpired`, `Unauthorized`, `Timeout`, `RateLimited`, `NodeUnreachable`, `Failed(routingError)`. |
| `SendFailure` | Sealed reason inside `SendState.Failed`, such as `NoRoute`, `RateLimited`, `AckTimeout`, `QueueRejected(res)`, or `Other(routingError)`. `PayloadTooLarge` isn't one: `send()` throws it. |
| `MeshEvent` | Sealed advisory stream on `RadioClient.events`. [error-taxonomy.md](./error-taxonomy.md#meshevent-reference) lists the variants. |

## Transports

| Term | Definition |
|---|---|
| Bluetooth GATT | The Bluetooth link: `toradio` (write), `fromradio` (read), `fromnum` (notify), and `logradio` (device logs). `protocol.md` §3. |
| TCP phone API | Port 4403 by default, with the same 4-byte stream header as serial. Radios advertise it over mDNS as `_meshtastic._tcp`. |
| Serial phone API | USB CDC or UART, with the 4-byte stream header and resync on its magic bytes. `protocol.md` §2. |
| HTTP phone API | `/api/v1/fromradio` (poll) and `/api/v1/toradio` (POST). The SDK has no HTTP transport. `protocol.md` §4. |
| WebSocket phone API | One `ToRadio` or `FromRadio` per binary message at `/api/v1/ws`, without the stream header; one client at a time. `:transport-websocket`. `protocol.md` §4. |
| MQTT client proxy | The node reaches an MQTT broker through the client: traffic arrives as `MeshEvent.MqttProxyMessage` and goes back with `sendRaw`. `protocol.md` §14. |

## Cross-references

- [protocol.md](./protocol.md): the wire specification
- [decisions/](./decisions/): ADRs
- [handshake-fsm.md](./architecture/handshake-fsm.md), [engine-actor.md](./architecture/engine-actor.md), [module-graph.md](./architecture/module-graph.md)
- [api-reference.md](./api-reference.md): public signatures
- [error-taxonomy.md](./error-taxonomy.md): errors and their wire mapping
