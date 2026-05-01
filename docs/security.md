# Security & threat model

> Scope: what `meshtastic-sdk` is and isn't responsible for protecting, where keys live, and which attack vectors the SDK design considers. Not a substitute for a formal security audit — pre-1.0 this is a working document for review.

## What's in scope

- The bytes between the host (phone/desktop/server running the SDK) and the device.
- Cryptographic material the SDK handles in transit or persists: channel PSKs, the device's `session_passkey`, PKI public keys exchanged via the device.
- The SDK's persistence layer (`DeviceStorage`) — what it stores, what callers must protect.
- The transport adapters' attack surface (BLE pairing, TCP/HTTP endpoints, serial enumeration).

## What's out of scope

- The mesh-side wire crypto. Channel encryption (AES-CTR/AES-256) and end-to-end PKI DM encryption are performed **by the firmware**, not by the SDK. The SDK never holds private keys for PKI DMs and never decrypts mesh PKI traffic — the device decrypts and hands the SDK the cleartext over PhoneAPI. (See [`references/meshtastic-firmware-behavior.md`](./references/meshtastic-firmware-behavior.md) lines 198-212.)
- The host process's security posture. If the host process is compromised, all bets are off.
- Network-layer attacks against the underlying radio (RF jamming, replay over the air). The SDK is downstream of these.
- The MQTT broker and the firmware's MQTT side-channel — `meshtastic/mqtt-client` owns broker auth/TLS for direct broker use; PhoneAPI MQTT-proxy mode (`protocol.md` §14) is a transparent byte relay where credentials live on the device.

## Asset inventory

| Asset | Sensitivity | Where it lives in the SDK | Lifetime |
|---|---|---|---|
| **Channel PSK** | High — symmetric mesh-channel key | `Channel` proto inside `ConfigBundle`, persisted by `DeviceStorage`; passed in-memory through engine for decrypt | Until channel rotated or storage cleared |
| **`session_passkey`** | Medium — 8-byte token gating admin RPCs for one connection | In-memory only inside `CommandDispatcher` while connected | Per connection (not persisted) |
| **Device PKI public key** (own + peers) | Low — public material | `NodeInfo.public_key`; persisted by `DeviceStorage` | Until node leaves NodeDB |
| **Device PKI private key** | Critical | **Never present in SDK.** Lives only on the device; SDK has no API to extract it. | n/a |
| **Inbound mesh-channel cleartext** (`MeshPacket.decoded`) | Varies (text/position/telemetry) | Engine memory, `packets` Flow, optionally storage | Per packet; no persistence by default |
| **Outbound mesh-channel cleartext** (caller-supplied) | Caller's responsibility | Engine memory, `outbound` Channel | Until handed to transport |
| **Storage on disk** (NodeDB, channels incl. PSKs, configs) | High (PSKs are key material) | `:storage-sqldelight` SQLite DB | Until consumer deletes |

## Trust boundaries

```
┌─ Mesh (RF, untrusted) ─┐
│ Other nodes, repeaters │
└────────────┬───────────┘
             │ wire-crypto enforced by firmware (PSK + PKI)
┌────────────▼───────────┐
│ Local Meshtastic device│ ← trusted; holds private keys
└────────────┬───────────┘
             │ PhoneAPI (BLE GATT / TCP / Serial / HTTP)
             │ ── trust boundary ──
┌────────────▼───────────┐
│ Host process running   │ ← trusted with everything below this line
│ meshtastic-sdk         │
└────────────┬───────────┘
             │ Storage write (DeviceStorage abstraction)
┌────────────▼───────────┐
│ Persistent storage     │ ← consumer-controlled; SDK does NOT encrypt at rest
└────────────────────────┘
```

The SDK assumes the link between the host process and the device is **untrusted but not actively MITM'd over BLE/serial** — see "Per-transport posture" below.

## Per-transport posture (MVP)

| Transport | Confidentiality of PhoneAPI link | Notes |
|---|---|---|
| **BLE GATT** | None at PhoneAPI layer; relies on BLE pairing/bonding | Hosts SHOULD use bonded connections (Android: `createBond()`; iOS: standard CB pairing). Unpaired BLE traffic is sniffable from ~10 m. |
| **TCP** | None | TCP/4403 is plaintext. **Do not expose to untrusted networks.** Recommend SSH tunneling or a VPN if the radio is remote. |
| **Serial (USB)** | Physical access required | Treat physical access to the device or USB cable as a full compromise vector. |

Roadmap transports (`transport-mqtt-proxy`, `transport-rpc`, `transport-http`) carry their own posture rows in [`./future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md) and are not part of the MVP threat model.

## Storage at rest

`DeviceStorage` implementations persist channel PSKs and the NodeDB. **The SDK does not encrypt at rest.** Consumers requiring at-rest protection must:

- Wrap their `StorageProvider` with platform-native encrypted storage:
  - Android: `EncryptedSharedPreferences` / `EncryptedFile` (Jetpack Security).
  - iOS: Keychain-backed file or `NSFileProtectionComplete` flags via `kotlinx-io` file backend.
  - JVM desktop: OS keystore + a KEK that decrypts the SQLDelight DB at startup.
- Or, in headless server contexts, run on a filesystem with full-disk encryption.

`:storage-sqldelight` will optionally accept a SQLCipher driver in a follow-up artifact (`:storage-sqldelight-cipher`); pre-1.0 this is not in scope.

## `session_passkey` handling

- **Never persisted.** Engine holds it in `CommandDispatcher` for the connection's lifetime.
- **Reset on every reconnect.** A fresh `get_owner_request` after handshake re-issues the value.
- **Single in-flight retry on `ADMIN_BAD_SESSION_KEY`:** the engine refreshes once, replays the original admin call once, then surfaces `AdminResult.SessionKeyExpired` if still rejected. (Documented in [`error-taxonomy.md`](./error-taxonomy.md).)

## Logging hygiene

`LogSink` MAY receive sensitive material if abused. Rules enforced by the engine:

- Channel PSKs are NEVER logged at any level. Internal `toString()` helpers redact them (`Channel.psk = <16 bytes redacted>`).
- `session_passkey` is NEVER logged.
- PKI public keys MAY be logged at `Debug` (they're public).
- `MeshPacket.decoded.payload` MAY be logged at `Verbose` only. Production consumers should run with `Info` or higher.
- Frame-level byte dumps are gated behind `LogLevel.Verbose` and the Builder opt-in `Builder.protocolLogging(level, redactor)` (see `SPEC.md` §3.1); off by default.

PSK redaction is a code-review invariant: reviewers inspect any new `LogSink.log(...)` call and reject interpolation of `psk` or `session_passkey` field names. Detekt's log-call inspection helps surface obvious cases, but there is no automated rule that proves redaction.

## Threat model: what the SDK defends against

1. **Accidental on-disk leak of PSK to an untracked location.** Mitigation: only `DeviceStorage` writes PSKs; engine never tee's into a file or log. Consumer's `LogSink` impl is the only escape.
2. **Stale NodeDB corrupting a swapped/factory-reset device's state.** Mitigation: `recordOwnNode` NodeNum-mismatch atomic-clear (ADR-005); fresh handshake repopulates.
3. **Replay/identity confusion across address changes.** Mitigation: `TransportIdentity` is a stable cache key; the NodeNum-mismatch rule handles physical-radio swaps behind the same address.
4. **Unauthorized admin RPCs via leaked `session_passkey`.** Mitigation: passkey not persisted; rotates per connection. An attacker would need both the live transport socket AND access to engine memory.
5. **Backpressure-induced silent data loss.** Mitigation: `MeshEvent.PacketsDropped` is mandatory and observable.
6. **Out-of-spec wire data crashing the engine.** Mitigation: `WireCodec` rejects malformed envelopes; `MeshtasticException.Protocol` surfaces; engine continues. Property-based tests (Kotest) fuzz the codec.

## What the SDK does NOT defend against

1. **A compromised host process.** If the host can read SDK memory, it can read PSKs, session passkeys, and cleartext mesh packets. This is true of any library.
2. **A compromised firmware.** The SDK trusts what the device tells it. A malicious device can lie about NodeDB contents, claim arbitrary `session_passkey`s, etc.
3. **Physical access to the device.** USB serial gives full PhoneAPI access. The device's own admin-key gating (firmware feature) is the appropriate mitigation; the SDK exposes it via `AdminApi`.
4. **Eavesdropping on TCP/4403.** As stated above — host's responsibility.
5. **Side-channel attacks against AES on the device.** Out of scope; firmware concern.

## Reporting vulnerabilities

Use the org's coordinated-disclosure process. See [`/SECURITY.md`](../SECURITY.md) at the repo root for the supported channels (private GitHub Security Advisory or the contacts listed there).

Vulnerabilities in the underlying firmware go to the `meshtastic/firmware` security process.

## Related

- [`protocol.md`](./protocol.md) §9 (channel encryption), §10 (PKI DMs), §13 (admin/session_passkey)
- [`references/meshtastic-firmware-behavior.md`](./references/meshtastic-firmware-behavior.md) — confirms the device, not the SDK, decrypts PKI
- [ADR-005](./decisions/005-api-shape.md) — `recordOwnNode` rebind contract
- [`error-taxonomy.md`](./error-taxonomy.md) — `SessionKeyExpired` retry behavior
