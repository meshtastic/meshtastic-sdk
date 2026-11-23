# Security and threat model

This page sets out what `meshtastic-sdk` protects and what it leaves to the host, where key material lives, and which attacks the design considers. It is a working document for review, not a substitute for a security audit.

## What's in scope

- The bytes between the host (the phone, desktop or server running the SDK) and the node.
- Key material the SDK handles in transit or persists: channel pre-shared keys (PSKs), each node's `session_passkey`, and PKI public keys learned through the node.
- The persistence layer (`DeviceStorage`): what it stores and what callers must protect.
- The transports' attack surface: Bluetooth pairing, TCP and WebSocket endpoints, serial enumeration, and mDNS and BLE discovery.

## What's out of scope

- Mesh-side wire crypto. Channel encryption (AES-CTR) and PKI direct-message encryption run in firmware. The SDK holds no PKI private key and decrypts no live mesh traffic: the node hands it cleartext over the PhoneAPI ([`protocol.md` §10](./protocol.md#key-material-storage), citing firmware `CryptoEngine.cpp`). SF++ replays are the exception: the SDK decrypts them with the channel key the node already shares with it ([`protocol.md` §9](./protocol.md#sf-replays-the-one-place-the-sdk-decrypts)).
- The security of the host process. A compromised host process exposes everything.
- Attacks on the radio link, such as RF jamming or over-the-air replay. The SDK is downstream of them.
- The MQTT broker and the firmware's MQTT connection. A consumer's own MQTT client owns broker authentication and TLS. In PhoneAPI MQTT proxy mode ([`protocol.md` §14](./protocol.md#14-mqtt-proxy-mode)) the consumer relays bytes and the broker settings live on the node.

## Asset inventory

| Asset | Sensitivity | Where it lives in the SDK | Lifetime |
|---|---|---|---|
| Channel PSK | High: symmetric channel key | `Channel` proto in `RadioClient.channels`, persisted by `DeviceStorage`; also inside raw envelopes on `RadioClient.fromRadio` | Until the channel is rotated or storage cleared |
| `session_passkey` | Medium: 8-byte token gating admin requests to one node | Engine memory, per node; `RadioClient.adminSessions` exposes state and timestamps, never the bytes | Until it expires, the connection ends or the local identity changes; never persisted |
| PKI public keys (own and peers) | Low: public | `User.public_key` in the NodeDB, persisted by `DeviceStorage` | Until the node leaves the NodeDB |
| PKI private key | Critical | Never present in the SDK. It lives only on the node, and the SDK has no API to extract it. | n/a |
| Inbound cleartext (`MeshPacket.decoded`) | Varies: text, position, telemetry | Engine memory, the `packets` and `fromRadio` flows | Per packet; not persisted |
| Outbound cleartext (caller-supplied) | Caller's responsibility | Engine memory, the outbound queue | Until handed to the transport |
| Storage on disk (NodeDB, channels with PSKs, configs) | High: PSKs are key material | `:storage-sqldelight` SQLite database | Until the consumer deletes it |

## Trust boundaries

```text
┌─ Mesh (RF, untrusted) ─┐
│ Other nodes, repeaters │
└────────────┬───────────┘
             │ wire crypto enforced by firmware (PSK + PKI)
┌────────────▼───────────┐
│ Local Meshtastic node  │ ← trusted; holds private keys
└────────────┬───────────┘
             │ PhoneAPI (BLE GATT / TCP / serial / WebSocket)
             │ ── trust boundary ──
┌────────────▼───────────┐
│ Host process running   │ ← trusted with everything below this line
│ meshtastic-sdk         │
└────────────┬───────────┘
             │ Storage write (DeviceStorage abstraction)
┌────────────▼───────────┐
│ Persistent storage     │ ← consumer-controlled; SDK does not encrypt at rest
└────────────────────────┘
```

The SDK treats the link between host and node as untrusted but not actively attacked; the next section gives each transport's posture.

## Per-transport posture

| Transport | Confidentiality of the PhoneAPI link | Notes |
|---|---|---|
| BLE GATT | Only what Bluetooth pairing and bonding provide | Use bonded connections (Android `createBond()`, or the opt-in bond-first step in `BleResilience`; standard CoreBluetooth pairing on iOS). Unpaired traffic can be sniffed from about 10 m. |
| TCP | None | Port 4403 is plaintext. Do not expose it to untrusted networks; use an SSH tunnel or VPN for a remote node. |
| Serial (USB) | Physical access required | Treat physical access to the node or the cable as full compromise. |
| WebSocket | None over `ws://`; TLS over `wss://` | The node presents a self-signed certificate. The host decides whether to trust it, through a custom Ktor `HttpClient` or, in a browser, the browser's own trust store. An `https` page can open `ws://` only to `localhost`. |

Discovery is unauthenticated. mDNS (`TcpDiscovery`) and BLE scan results (`BleScanner`) report whatever answers on the network or the air, and `bondedBleDevices(context)` lists every bonded device, so a host should confirm a node's identity after connecting rather than trust a discovered name.

Planned transports such as `transport-rpc` carry their own posture in [`./future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md).

## Storage at rest

`DeviceStorage` implementations persist channel PSKs and the NodeDB, and the SDK does not encrypt them. A consumer that needs protection at rest has two options:

- Protect the SQLDelight database file with platform storage protection: an encrypted file or Keystore-held key on Android, `NSFileProtectionComplete` or Keychain-backed key material on iOS, and an OS keystore with a key-encryption key on desktop JVM.
- On a headless server, run on a filesystem with full-disk encryption.

A follow-up artifact accepting a SQLCipher driver (`:storage-sqldelight-cipher`) is out of scope before 1.0.

## `session_passkey` handling

- Never persisted. The engine keeps each node's key in memory.
- Never exposed. `RadioClient.adminSessions` reports per-node status and timestamps only.
- Dropped when the connection ends or the local identity changes. The handshake's `get_owner_request` seeds a fresh key for the local node.
- On `ADMIN_BAD_SESSION_KEY` the engine re-seeds once and replays the request once, then returns `AdminResult.SessionKeyExpired` if it is still rejected ([`error-taxonomy.md`](./error-taxonomy.md)).

## Logging hygiene

`LogSink` receives what the engine logs, and whatever a consumer chooses to log.

- The engine's own log calls do not interpolate channel PSKs or session passkeys.
- `Builder.protocolLogging(level, redactor)` is off by default (`LogLevel.NONE`). Its default `PayloadRedactor.Default` renders each envelope as its variant, routing metadata and flow-control counters only: no payload bytes, text, positions, PSKs, passkeys, keys, config values or file contents. A custom `PayloadRedactor` sees whole envelopes and is responsible for what it prints.
- `RadioClient.fromRadio` emits raw envelopes, including channel PSKs and positions, unredacted. A consumer that records it is storing key material.
- Wire-generated `toString()` does not redact `bytes` fields, so logging a `Channel` or `Config` prints its PSK.

PSK redaction is a code-review invariant: reviewers reject any new log call that interpolates `psk` or `session_passkey`. No automated rule proves it.

## Threat model: what the SDK defends against

1. Accidental on-disk leak of a PSK. Only `DeviceStorage` writes PSKs, and the engine never copies them into a file or log. The consumer's `LogSink`, a custom `PayloadRedactor` and the `fromRadio` tap are the only other ways out.
2. A stale NodeDB carried into a swapped or factory-reset node's session. When the reported NodeNum changes, `recordOwnNode` clears storage atomically and the fresh handshake repopulates it. The exception is a node that renumbers under the same trusted `device_id`, which keeps its storage ([`architecture/storage.md`](./architecture/storage.md#renumbered-device-same-device_id)).
3. Identity confusion across address changes. `TransportIdentity` is a stable cache key, and the NodeNum check catches a physical swap behind the same address.
4. Admin requests through a leaked `session_passkey`. Keys are not persisted, expire within 300 s, and are dropped at disconnect. An attacker needs both the live transport link and access to engine memory.
5. Silent data loss under backpressure. `MeshEvent.PacketsDropped` is emitted whenever the engine drops packets.
6. Out-of-spec wire data crashing the engine. `WireCodec` rejects malformed envelopes, `MeshtasticException.Protocol` surfaces, and the engine continues. Kotest property tests fuzz the codec.

## What the SDK does not defend against

1. A compromised host process, which can read PSKs, session passkeys and cleartext packets from memory. This is true of any library.
2. Compromised firmware. The SDK trusts what the node reports, including NodeDB contents and session passkeys.
3. Physical access to the node. USB serial gives full PhoneAPI access. The node's admin-key gating is the mitigation, and the SDK exposes it through `AdminApi`.
4. Eavesdropping on TCP port 4403 or `ws://`. Protecting those links is the host's responsibility.
5. Side-channel attacks on AES in the node, which are a firmware concern.

## Reporting vulnerabilities

Use the org's coordinated-disclosure process; [`SECURITY.md`](../SECURITY.md) at the repository root lists the channels (a private GitHub Security Advisory or the listed contacts). Report firmware vulnerabilities through the `meshtastic/firmware` security process.

## Related

- [`protocol.md`](./protocol.md) §9 (channel encryption), §10 (PKI direct messages), §13 (admin and session passkey)
- [`protocol.md` §7 payload paths](./protocol.md#payload-paths): the node, not the SDK, decrypts channel and PKI traffic
- [ADR-005](./decisions/005-api-shape.md): the `recordOwnNode` rebind contract
- [`error-taxonomy.md`](./error-taxonomy.md): `SessionKeyExpired` retry behavior
