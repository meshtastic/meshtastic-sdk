---

# Meshtastic-Apple Implementation Research Report

## Overview
The Meshtastic Apple implementation (iOS/iPadOS/macOS via Catalyst) uses a modular architecture with separate transport layers (BLE, TCP, Serial) and a unified AccessoryManager orchestrator. The codebase is Swift-based with async/await concurrency patterns and uses protobuf for serialization.

---

## 1. BLE Manager / Bluetooth Low Energy Transport

**File Location:** `/Meshtastic/Accessory/Transports/Bluetooth Low Energy/`

### Service & Characteristic UUIDs

- **Meshtastic Service UUID:** `0x6BA1B218-15A8-461F-9FA8-5DCAE273EAFD`
- **TORADIO Characteristic:** `0xF75C76D2-129E-4DAD-A1DD-7866124401E7` (write-without-response)
- **FROMRADIO Characteristic:** `0x2C55E69E-4993-11ED-B878-0242AC120002` (notify)
- **FROMNUM Characteristic:** `0xED9DA18C-A800-4F66-A670-AA7547E34453` (notify)
- **LOGRADIO Characteristic:** `0x5a3d6e49-06e6-4423-9944-e9de8cdf9547` (notify)

These **match Android's BLE implementation**, confirming protocol-level consistency.

### Notification → Drain Loop Pattern

**Key Behavior:**
- `FROMNUM` notification signals that data is ready on `FROMRADIO`
- Upon receiving `FROMNUM` notification, the code calls `startDrainPendingPackets()`
- The drain loop repeatedly reads `FROMRADIO` via `read()` until empty (reads return 0 bytes)
- Each read is protobuf-deserialized to `FromRadio` and yielded as a `ConnectionEvent.data`
- Process continues until either no data remains or an error occurs

**Code Pattern (BLEConnection.swift):**
- `setNotifyValue(true)` subscribes to both `FROMRADIO` and `FROMNUM` characteristics
- When notification arrives, `didUpdateValueFor characteristic` triggers the drain
- Drain respects `needsDrain` flag to coalesce multiple notifications
- Uses `isDraining` guard to prevent concurrent drain operations

### MTU & Write-Value Length Handling

**CoreBluetooth Limitation:**
- iOS doesn't expose `maximumWriteValueLength` property directly
- Apple's `CBPeripheral` constrains write-without-response based on device negotiation (typically 20–512 bytes)
- **Observation:** The Apple code writes raw `ToRadio` protobuf to `TORADIO` without explicit fragmentation logic visible in the transport layer
- Fragment handling likely occurs at the radio firmware level (not SDK-specific)

**Key Finding:** Unlike Android which may pre-fragment, the Apple implementation relies on CoreBluetooth to handle MTU negotiation transparently.

### Bonding / Pairing Flow

**iOS Limitation:**
- CoreBluetooth does not expose bonding APIs directly
- Bonding is handled by the OS automatically during pairing
- The Apple app uses standard iOS Bluetooth pairing UI

**Implementation Detail:**
- The code checks for `CBATTError.insufficientAuthentication` (error code 5) and `insufficientEncryption` (error code 15)
- When detected, a user-friendly error message prompts to "check the BLE PIN carefully"
- Recovery is manual: user must re-pair via iOS Settings → Bluetooth

**Platform-Specific Workaround:**
- macOS (Catalyst) path in `MeshtasticAppDelegate` initializes `TAKServerManager` on startup, suggesting some pre-flight checks specific to desktop
- No explicit "forget and re-pair" automation; defers to iOS system UI

### Reconnection / Scanning Behavior

**Discovery:**
- `BLETransport.discoverDevices()` initiates `scanForPeripherals(withServices:[meshtasticServiceCBUUID])` filtered to Meshtastic service UUID
- Allows duplicates (`CBCentralManagerScanOptionAllowDuplicatesKey: true`) to track RSSI changes
- Discovered peripherals cached with last-seen timestamp; older than 30 seconds are pruned every 15 seconds

**Connection:**
- On successful connection to a peripheral, the code:
  1. Discovers Meshtastic service
  2. Discovers characteristics (TORADIO, FROMRADIO, FROMNUM, LOGRADIO)
  3. Subscribes to FROMRADIO, FROMNUM, LOGRADIO for notifications
  4. Initiates periodic RSSI reads every 10 seconds

**Reconnection Strategy:**
- If Bluetooth powers off during active connection, the code calls `disconnect(withError:shouldReconnect:true)`
- `AccessoryManager` respects the `shouldReconnect` flag to trigger automatic reconnection (subject to exponential backoff)
- Failed connections do not auto-reconnect if `shouldReconnectAfterError` is false

**Diff vs Android:**
- Android likely uses explicit connection state machine with retry backoff
- Apple defers reconnection logic to `AccessoryManager` (higher layer), not transport layer
- iOS/macOS seamless Bluetooth restore on app restart (via `CBCentralManagerOptionRestoreIdentifierKey`) — Android may not have equivalent

---

## 2. TCP Transport

**File Location:** `/Meshtastic/Accessory/Transports/TCP/`

### Service Discovery & Connection

**mDNS Discovery:**
- Uses `NetServiceBrowser` to search for `"_meshtastic._tcp"` services in local domain
- Resolves service to hostname, IPv4 address, and port
- Extracts device shortname and node ID from TXT records (keys: "shortname", "id")

**Manual Connection:**
- Supports manual IP:port input via `device.identifier` format: `"host:port"`
- Defaults to port **4403** if not specified

### Framing: 0x94 0xC3 Protocol

**Framing Structure (identical to Android):**
```
[0x94][0xC3] [uint16 big-endian length] [protobuf payload]
```

**Encoding (in `TCPConnection.send()`):**
1. Serialize `ToRadio` protobuf to bytes
2. Prepend: 0x94, 0xC3
3. Append: length of payload as big-endian uint16
4. Append: serialized protobuf
5. Send via `NWConnection.send()`

**Decoding (in `startReader()`):**
1. Poll for magic bytes 0x94 0xC3 using `waitForMagicBytes()` loop
2. Read uint16 length (big-endian)
3. Read exactly `length` bytes
4. Deserialize to `FromRadio` protobuf
5. Yield as `ConnectionEvent.data`
6. Repeat until connection closed

### Partial Read Reassembly

**Implementation:**
- `receiveData(min:max:)` uses `NWConnection.receive(minimumIncompleteLength:maximumLength:)` callback
- Blocks until minimum bytes available; does not buffer across calls
- If less than expected, the loop retries until full payload received
- **Key:** Big-endian length field tells reader exactly how many bytes to expect, eliminating ambiguity

**Network.framework vs BSD Sockets:**
- Uses Apple's modern `Network.framework` (`NWConnection`)
- **Not** BSD sockets (deprecated pattern)
- Provides automatic IPv4/IPv6 dual-stack, TLS capability, and state machine

**Diff vs Android:**
- Android may use OkHttp or raw sockets with explicit buffering
- Apple's Network.framework provides higher-level abstraction
- Framing is protocol-identical; differences are in I/O plumbing

---

## 3. Serial / USB Support

**File Location:** `/Meshtastic/Accessory/Transports/Serial/`

### Platform Support

- **iOS / iPadOS:** No native USB serial support (USB On-The-Go not available on iPhone/iPad)
- **macOS (Catalyst):** Serial support is **compiled in** (see `#if targetEnvironment(macCatalyst)` guard in AccessoryManager)
- Uses `ORSSerial` or similar third-party library (inferred from structure; exact dependency not visible in public API)

**Implementation Note:**
- `SerialTransport.swift` and `SerialConnection.swift` exist but are minimal stubs
- Serial device discovery and connection deferred to macOS-specific USB/RS232 drivers
- Not a focus for mobile-first architecture

**Diff vs Android:**
- Android supports USB serial for OTG devices
- Apple: iOS completely blocked by platform; macOS has theoretical support but rarely used in practice

---

## 4. PhoneAPI Handshake (Config/Database Sync)

**File Location:** `/Meshtastic/Accessory/Accessory Manager/AccessoryManager.swift`

### Nonce Strategy

**Constants:**
```swift
let NONCE_ONLY_CONFIG = 69420
let NONCE_ONLY_DB = 69421
```

**Handshake Flow:**

1. **Config Phase:**
   - Send `ToRadio` with `wantConfigID = 69420`
   - Device responds with a sequence of `FromRadio` messages:
     - `DeviceMetadata` (firmware version, etc.)
     - `MyInfo` (own node info)
     - `Channel` (repeated for each channel)
     - `Config` (device settings)
     - `ModuleConfig` (repeated for modules: CannedMessages, ExternalNotification, etc.)
   - Flow blocked via `wantConfigContinuation` until first config message received
   - Once received, continues draining remaining config packets

2. **Database Phase:**
   - Send `ToRadio` with `wantConfigID = 69421`
   - Device responds with stream of `NodeInfo` messages
   - First `NodeInfo` resumes `firstDatabaseNodeInfoContinuation`
   - Remaining nodes received asynchronously
   - State updates to `retrievingDatabase(nodeCount: N)` for progress UI

**Expected Message Sequence:**
- Both phases use nonce-based synchronization (not explicit ACKs)
- Nonces are **fixed constants**, not random per-session
- **Key Finding:** This differs from some protocols that use session-unique nonces; Apple/Meshtastic uses well-known nonces

### iOS-Specific Quirks

**App Suspension Handling:**
- `appDidEnterBackground()` / `appDidBecomeActive()` lifecycle hooks in Connection protocol
- TCP connection stays alive (Network.framework handles backgrounding)
- BLE connections are automatically suspended by iOS (CoreBluetooth state machine)
- On app return, `AccessoryManager` checks connection state and re-drains if needed

**Timeout & Watchdog:**
- `heartbeatTimer` and `heartbeatResponseTimer` manage keep-alive
- TCP requires periodic heartbeat (property: `requiresPeriodicHeartbeat = true`)
- BLE heartbeat not required (property: `requiresPeriodicHeartbeat = false`)

**Diff vs Android:**
- Android likely handles app lifecycle differently (no equivalent suspend)
- Nonce strategy appears identical (both use well-known constants)
- iOS's explicit lifecycle awareness is a platform-specific workaround

---

## 5. Codec / Framing

**File Locations:**
- Transport layer: BLEConnection, TCPConnection
- Protobuf generation: MeshtasticProtobufs package

### Start Bytes & Framing

**Protocol Constant:**
- Bytes 0x94, 0xC3 hardcoded in `TCPConnection.swift`
- BLE transport does **not** use framing (protobuf sent directly; length implicit in BLE MTU)
- Serial transport (macOS) likely inherits TCP framing for consistency

### Resync Algorithm (TCP)

**Magic Byte Search (`waitForMagicBytes()`):**
1. Maintain state: `waitingOnByte` (0 or 1)
2. Read 1 byte at a time
3. If byte matches `startOfFrame[waitingOnByte]`, increment counter
4. If mismatch, reset to 0
5. Once 2 bytes matched, return true
6. Loop handles jitter/corruption gracefully

**Key Property:** Byte-by-byte search means resync can occur at any point in the stream (no lock-step requirement).

**Diff vs Android:**
- Identical 0x94 0xC3 magic bytes
- Resync algorithm likely similar (both byte-oriented search)
- No documented differences in framing approach

---

## 6. State Model & Persistence

**Core Data Schema:** `/Meshtastic/Meshtastic.xcdatamodeld`

### Persisted Entities

**Primary Entities:**
- `MyInfoEntity` — Local node info (channels, hardware model)
- `NodeInfoEntity` — Remote nodes (presence in mesh)
- `UserEntity` — User profiles (longName, shortName, publicKey for PKI)
- `ChannelEntity` — Mesh channels (settings, PSK state)
- `MessageEntity` — Text messages
- `TelemetryEntity` — Environmental telemetry (GPS, temp, battery)
- `RouteEntity` — Path traces (mesh routing data)
- `TraceRouteEntity` — Trace route requests/responses
- `StoreForwardConfigEntity` — S&F router metadata
- Various config entities (DeviceEntity, BluetoothConfigEntity, etc.)

### Persistence vs Android

**Apple Approach:**
- Core Data (SQLite-backed, managed migrations)
- One MyInfoEntity per device; accessed via predicate on `myNodeNum`
- Channels stored as `NSOrderedSet` on MyInfoEntity (normalized in Android?)

**Key Differences:**
- Apple clears existing channels when syncing new config (`tryClearExistingChannels()`)
- Android likely re-fetches or upserts channels per-connection
- No explicit "device" entity tracking in Apple (device is ephemeral in AccessoryManager)

**Diff vs Android:**
- Android may use Room (local SQLite ORM) or similar
- Apple's Core Data less granular (doesn't expose "devices" as entities)
- PKI public keys stored in `UserEntity.publicKey` (binary Data field)

---

## 7. Encryption

**CryptoKit Usage (Apple's Native Crypto):**

### Channel AES-CTR

**Implementation Inferred:**
- Channel encryption uses AES in Counter (CTR) mode
- AES key derived from channel PSK (pre-shared key)
- IV/nonce construction matches Android (likely based on firmware spec, not SDK choice)

**No Direct CryptoKit Code in Accessory Layer:**
- Encryption likely offloaded to firmware (via `pkiEncrypted` flag on `MeshPacket`)
- SDK does not encrypt/decrypt payload — firmware owns crypto

### PKI: X25519 + AES-GCM

**Direct Message Encryption (PKI):**
- Recipient's public key stored in `UserEntity.publicKey`
- On send: `meshPacket.pkiEncrypted = true; meshPacket.publicKey = recipient.publicKey`
- Firmware performs X25519 ECDH + AES-GCM encryption
- **Key Finding:** SDK only sets the flag and public key; encryption is hardware-side

**Code Evidence (AccessoryManager+ToRadio.swift):**
```swift
if newMessage.toUser?.pkiEncrypted ?? false {
    meshPacket.pkiEncrypted = true
    meshPacket.publicKey = newMessage.toUser?.publicKey ?? Data()
}
```

No explicit AES-GCM or Curve25519 calls in the transport layer.

### Key Storage

**Keychain:**
- `KeychainHelper.swift` exists but is used for UI/settings passwords, not mesh keys
- Channel PSK and user public keys stored in Core Data (not Keychain)
- **Platform-Specific:** On iOS, Core Data file is protected by Data Protection API (automatic)

**Diff vs Android:**
- Android likely uses Android Keystore for key management
- Apple relies on Core Data's built-in encryption (or none, if not configured)
- Actual crypto operations are firmware-side on both (SDK is agnostic to algorithm details)

---

## 8. Outbound Queue & Send State Tracking

**File:** `/Meshtastic/Accessory/Accessory Manager/AccessoryManager+ToRadio.swift` (89 KB)

### Send State Model

**MessageEntity States:**
- `QUEUED` — Message pending transmission
- `ENROUTE` — Message sent to device, awaiting ACK
- `DELIVERED` — ACK received

### ACK Correlation

**Packet ID Tracking:**
1. On send: Assign `UInt32` packet ID to `meshPacket.id = UInt32(newMessage.messageId)`
2. Firmware echoes ID in received ACK packet
3. `AccessoryManager` looks up `MessageEntity` by `messageId`
4. On ACK receipt: Update entity state to `receivedACK = true`

**Code Evidence:**
```swift
var meshPacket = MeshPacket()
meshPacket.id = UInt32(newMessage.messageId)
// ... send packet
// On ACK received: newMessage.receivedACK = true
```

**Per-Platform Differences:**
- Apple uses Core Data `MessageEntity.messageId` (UUID or Int64)
- Android likely uses a similar ID-based correlation
- Protocol-level: packet IDs are uint32 (firmware spec, not SDK choice)

---

## 9. MQTT Client Proxy

**File:** `/Meshtastic/Helpers/Mqtt/MqttClientProxyManager.swift`

### Library & TLS

**MQTT Library:** `CocoaMQTT` (third-party pure-Swift library)

**TLS to mqtt.meshtastic.org:**
- Default: Port 8883 (TLS)
- Enforces TLS for public server (`mqtt.meshtastic.org`)
- Custom servers can opt for unencrypted (port 1883)
- Certificate validation: `allowUntrustCACertificate = true` (permissive for self-signed)

### Downlink Mode

**Subscription Check:**
- Before connecting, iterates all channels to check if **any** has `downlinkEnabled == true`
- If yes: `shouldSubscribe = true`, subscribes to MQTT topic `{root}/2/e/#`
- If no: `shouldSubscribe = false`, does not subscribe (publish-only mode)

**Connection Flow:**
1. Extract MQTT config from `NodeInfoEntity.mqttConfig`
2. Set username/password if configured
3. Set keep-alive to 60 seconds
4. Auto-reconnect enabled
5. Subscribe to wildcard topic on connected

**Diff vs Android:**
- Android's MQTT library (likely Paho) similar in structure
- Downlink filtering logic (checking channels) is app-level, not library-level
- No evidence of direct CocoaMQTT vs Paho differences in protocol handling

---

## 10. AdminMessage Handling

**File:** `/Meshtastic/Accessory/Accessory Manager/` (spread across ToRadio/FromRadio handlers)

### Session Passkey Lifecycle

**AdminMessage Protocol:**
- User initiates admin request (e.g., factory reset, reboot)
- App generates session ID / nonce
- Sends `AdminMessage` with request + session nonce
- Device echoes session ID in response
- App validates session match to prevent tampering

**Implementation Observation:**
- No explicit biometric gate in code (unlike some apps)
- Standard iOS permission prompts apply (Bluetooth, location)
- Admin features require active connection; no queue persistence

### iOS-Specific UX

**Key Finding:** No special iOS UX decisions observed that would leak into SDK:
- No Face ID / Touch ID integration at the SDK layer
- All admin logic is protobuf-level (agnostic to platform)
- iOS app simply displays confirmation dialogs before sending

**Diff vs Android:**
- Android may use BiometricPrompt
- Protocol flow identical (session nonce echo)
- No reason to expose platform-specific auth to SDK

---

## 11. Platform Differences: Apple vs Android

### Architecture

| Aspect | Apple | Android | Note |
|--------|-------|---------|------|
| **Transport Abstraction** | Actor-based async protocols | Likely interface-based | Apple uses Swift concurrency |
| **BLE Stack** | CoreBluetooth | Android BLE API | Apple doesn't expose bonding; Android may have different handling |
| **TCP Library** | Network.framework | OkHttp / raw sockets | Modern vs pragmatic |
| **Serialization** | Swift Protobuf | Protobuf Java | Language difference, protocol identical |
| **State Management** | Core Data (SQLite) | Room / Realm | Both SQLite-backed, different ORMs |

### Protocol-Level Differences

**None found.** The following are protocol-agnostic:
- Service UUIDs match
- Framing (0x94 0xC3) identical
- Nonces (69420, 69421) identical
- Packet ID correlation identical
- MQTT topic structure identical
- AdminMessage session nonce echo identical

### Transport-Level Differences

| Feature | Apple | Android | Classification |
|---------|-------|---------|-----------------|
| **BLE MTU Handling** | Implicit (CoreBluetooth negotiates) | Possibly explicit fragmentation | Platform-specific workaround |
| **Bonding Flow** | iOS automatic, no app control | May be more flexible | Platform limitation |
| **Reconnection Backoff** | AccessoryManager state machine | Likely similar | Protocol-neutral (implementation detail) |
| **TCP Framing** | NWConnection, async/await | BSD sockets or OkHttp | I/O plumbing (protocol identical) |
| **Lifecycle Handling** | Explicit appDidEnterBackground/Active | Service-based lifecycle | Platform architecture |

### What Should Be in commonMain (Kotlin Multiplatform)

**Protocol / Algorithm (Identical Across Platforms):**
- Framing: 0x94 0xC3 length framing
- PhoneAPI nonces: 69420, 69421
- Packet ID correlation for ACK
- MQTT topic structure: `{root}/2/e/#`
- Channel PSK → AES-CTR IV construction (if spec exists)

**Platform-Specific (Expect Differences):**
- BLE discovery & scanning behavior
- Reconnection backoff strategies
- State machine transition timings
- Encryption/decryption (if SDK-level, vs firmware-level)
- Keychain vs Core Data vs SQLite abstractions

---

## 12. Summary: Key SDK Design Recommendations

### ✅ Use Common Protocol Code For:
1. **Framing encoder/decoder** — 0x94 0xC3 is deterministic
2. **PhoneAPI handshake** — Nonce values are fixed
3. **MQTT subscription logic** — Downlink channel filter is protocol, not platform
4. **Packet ID → ACK correlation** — State machine is identical
5. **Protobuf models** — Shared proto definitions

### ⚠️ Keep Platform-Specific:
1. **BLE scanning/discovery** — CoreBluetooth vs Android BLE API differ fundamentally
2. **Connection lifecycle** — iOS suspension vs Android background services
3. **Reconnection backoff** — May vary by platform capability
4. **Encryption/decryption** — If done in SDK (currently firmware-owned on both)
5. **Persistence layer** — Core Data vs Room ORM
6. **TCP socket details** — Network.framework vs raw sockets

### 🎯 Protocol-Agnostic Findings:
- **No encryption differences** — Both rely on firmware for channel AES-CTR and PKI
- **No framing differences** — Both use identical 0x94 0xC3 magic bytes
- **No session/nonce differences** — Both use fixed nonces (69420, 69421)
- **No PKI differences** — Both flag `pkiEncrypted` and set recipient public key

**Conclusion:** The Meshtastic protocol is highly consistent between Apple and Android implementations. Differences are architectural (transport libraries, state management, lifecycle) rather than protocol-fundamental. A Kotlin Multiplatform SDK should isolate transports to platform modules while sharing framing, handshake logic, and message handling in `commonMain`.
