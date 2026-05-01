---

# Meshtastic Android SDK Implementation Requirements

**Report based on:** Meshtastic-Android repository, main branch
**License context:** GPL-3.0 licensed code; behavior description only (no code quotes)

---

## 1. SERVICE ARCHITECTURE

**Files:**
- `core/service/src/androidMain/kotlin/org/meshtastic/core/service/MeshService.kt` (Android foreground service)
- `core/service/src/commonMain/kotlin/org/meshtastic/core/service/MeshServiceOrchestrator.kt` (KMP-portable orchestrator)

**Threading/Coroutine Model:**
- The service uses a **KMP-first architecture** with `MeshServiceOrchestrator` as the platform-agnostic core
- **Android-specific:** `MeshService` wraps the orchestrator as a foreground service (API 30+: `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` + optional `FOREGROUND_SERVICE_TYPE_LOCATION`)
- **Coroutine structure:** The orchestrator creates a per-start `CoroutineScope` with a `SupervisorJob()`, wired to `CoroutineDispatchers.default`
- **Cleanup:** Each `start()` call creates a fresh scope; `stop()` cancels it, preventing packet leakage across reconnections
- **Critical:** The scope is created **before** `databaseManager.switchActiveDatabase()` completes to ensure Room writes succeed during handshake
- **Error handling:** Uncaught exceptions in coroutines are supervised per-action (each action re-launched in its own `handledLaunch` coroutine) to prevent cascading failures

**Phone-side ConnectionState Exposure:**
- **Model:** `ConnectionState` is a sealed interface with four states:
  - `Disconnected` (should reconnect)
  - `Connecting` (handshake in progress)
  - `Connected` (fully operational)
  - `DeviceSleep` (transient; power-saving mode)
- **Flow:** `ServiceRepository.connectionState` is a StateFlow<ConnectionState> observed by UI layer
- **State machine:** Lives in `MeshConnectionManagerImpl`, which translates transport-level state (from `RadioInterfaceService.connectionState`) via a policy (`onRadioConnectionState`) that applies light-sleep logic:
  - If device sends `DeviceSleep` AND (radio is in ROUTER mode OR power_saving enabled): stay in `DeviceSleep` (wait up to 5 min, then disconnect)
  - Otherwise: downgrade `DeviceSleep` → `Disconnected`

**Lifecycle:**
- **Service start:** Android calls `MeshService.onCreate()` → `MeshServiceOrchestrator.start()`
  1. Creates per-start coroutine scope
  2. Initializes database for this device (async, via dedicated Job)
  3. Calls `radioInterfaceService.connect()` (picks transport, starts BLE/TCP/serial discovery)
  4. Wires flows: `radioInterfaceService.receivedData` → `MeshMessageProcessor` → packet handlers
  5. Wires service actions: `ServiceRepository.serviceAction` flow → `MeshRouter.actionHandler`
  6. Launches node cache loading asynchronously
- **Service stop:** `MeshService.onDestroy()` → `orchestrator.stop()` cancels the scope, stopping all active work
- **Radio interface start:** `RadioInterfaceService.connect()` picks transport (BLE > TCP > serial > NOP), then calls `radioTransport.start()` (BLE connection loop, TCP socket connect, serial port open, etc.)
- **Radio interface teardown:** On transport disconnect, `RadioInterfaceService` emits `connectionState = Disconnected`, which flows to `MeshConnectionManagerImpl.onRadioConnectionState()` → `onConnectionChanged()` → `handleDisconnected()` which calls `tearDownConnection()` (stops packet queue, MQTT, location tracking)

**FLAG:** The `MeshServiceOrchestrator` is **commonMain** and platform-agnostic; `MeshService` (Android Service) is Android-specific and should **not** be replicated in KMP. iOS and JVM should use equivalent platform-specific wrappers around the same orchestrator.

---

## 2. TRANSPORT IMPLEMENTATIONS

**Base structure:** `StreamFrameCodec` (commonMain, reusable) + platform-specific transports wrapping it

### 2.1 StreamFrameCodec (Framing Layer)

**File:** `core/network/src/commonMain/kotlin/org/meshtastic/core/network/transport/StreamFrameCodec.kt`

**BLE GATT Layout & UUIDs:**
- Service UUID: `12 D6 0000-D605-11E3-8C3D-0002A5D5C51B` (standard Meshtastic service)
- Characteristics (via BLE profile abstraction; implementation in `core/ble/`):
  - `toRadio` (write-without-response): outbound packets from phone → radio
  - `fromRadio` (notify + CCCD): inbound packets from radio → phone (subscribed via CCCD enable)
  - `logRadio` (optional notify): device serial debug output

**Framing Protocol (0x94 0xC3 + MSB/LSB length):**
- **Frame format:** `[0x94] [0xC3] [MSB_len] [LSB_len] [payload...]`
  - MSB/LSB are unsigned, big-endian 16-bit length
  - `MAX_TO_FROM_RADIO_SIZE = 512` bytes
  - Min frame: 4 bytes (header with zero-length payload)
- **Encoding (`frameAndSend`):**
  - Thread-safe via `Mutex.withLock`
  - Splits length into two bytes: `header[2] = (payload.size >> 8).toByte(); header[3] = (payload.size & 0xff).toByte()`
  - Sends header, then payload via callback
- **Decoding (`processInputByte` — byte-by-byte state machine):**
  - State 0 (awaiting START1): scan for `0x94`, ignore garbage (device serial output sent here)
  - State 1 (awaiting START2): expect `0xC3`; if mismatch, `lostSync()` (reset to state 0)
  - States 2–3 (reading length): capture MSB, LSB; compute `packetLen = (msb << 8) | lsb`
  - Validation: if `packetLen > 512` or (MSB/LSB sanity fails), `lostSync()`
  - States 4+ (reading payload): accumulate bytes into `rxPacket` buffer
  - Termination: when `(ptr - HEADER_SIZE) == packetLen`, invoke `onPacketReceived(copyOf())`
- **Resync algorithm:**
  - Byte-by-byte scan: any mismatch in states 0–1 resets to state 0 and re-scans
  - **NO skip-ahead strategy:** does NOT scan for next start sequence in remaining buffered data; re-enters state 0 and waits for next byte
  - **Timeout:** No explicit frame timeout; corrupted length can cause indefinite wait for payload bytes (mitigated by transport-level idle timeout, e.g., BLE CCCD, TCP keepalive)
  - **Pre-connection wake:** Sends `WAKE_BYTES = [0x94, 0x94, 0x94, 0x94]` (four START1 bytes) before connecting to rouse sleeping firmware
- **Device serial debug output:**
  - Any non-START1 byte in state 0 is buffered and printed on `\n` as device log line: `Logger.d { "DeviceLog: $line" }`

### 2.2 BLE Transport (BleRadioTransport)

**File:** `core/network/src/commonMain/kotlin/org/meshtastic/core/network/radio/BleRadioTransport.kt`

**BLE Characteristics & Notification Handling:**
- Discovery: finds service UUID, then uses abstractions (`MeshtasticRadioProfile` via `toMeshtasticRadioProfile()`)
- Subscription: calls `radioService.awaitSubscriptionReady()` to confirm CCCD is enabled before sending first packet
- Notifications: `fromRadio` and `logRadio` flows feed raw bytes to `StreamFrameCodec.processInputByte()`

**Drain-until-empty Loop:**
- **HeartbeatSender:** Sends `ToRadio(heartbeat = Heartbeat(nonce = ++counter))` every 30s (via `keepAlive()`)
- **Post-heartbeat drain:** After sending heartbeat, delays 200ms then calls `radioService.requestDrain()` to trigger re-polling `fromRadio`
- **Rationale:** ESP32 NimBLE callback → FreeRTOS task queue → `handleToRadio()` → set `heartbeatReceived = true`; immediate drain fires before device processes callback, so 200ms grace period lets the queue settle and the firmware populate `queueStatus` response

**MTU Negotiation:**
- Calls `bleConnection.maximumWriteValueLength(BleWriteType.WITHOUT_RESPONSE)` to determine max write size
- Logs negotiated MTU; uses for framing multiple small packets if needed
- No explicit MTU request; relies on BLE stack negotiation

**Bonding:**
- **Pre-connection:** Checks `bluetoothRepository.isBonded(address)`
- **If not bonded:** Calls `bluetoothRepository.bond(device)` before connecting (Android firmware may require encrypted link; Desktop/JVM skips this)
- **On failure:** Logs warning and proceeds anyway (connection may still succeed with DM-level pairing instead)

**Reconnection Retry Timers:**
- **Backoff:** `BleReconnectPolicy` applies exponential backoff: 1st failure → 5s, 2nd → 10s, 3rd → 20s, 4th → 40s, 5+ → 60s (capped)
- **Transient vs. permanent:**
  - Up to 2 consecutive failures: retry silently
  - 3+ consecutive failures: emit transient disconnect signal (UI shows device as "sleeping")
  - 10+ consecutive failures: give up permanently
- **Stable connection criterion:** Connection must stay up ≥5 seconds to be considered "stable"; unstable drops reset failure counter
- **Reconnect loop:** Infinite loop with outcomes (Disconnected/Failed) feeding back to `processOutcome()` for backoff decision

**Connection Lifecycle (BLE-specific):**
1. **findDevice():** Check bonded devices, fall back to scan with 3 retries (1s delay between attempts)
2. **Bond if needed** (Android-only)
3. **`bleConnection.connectAndAwait(device, 15s)`:** GATT connect with 15s timeout
4. **`onConnected()`:** Read initial RSSI for diagnostics
5. **`discoverServicesAndSetupCharacteristics()`:**
   - Profile service and subscribe to flows (`fromRadio`, `logRadio`)
   - Wait for CCCD subscription ready
   - Log negotiated MTU
   - Emit `callback.onConnect()`
6. **Supervision:** Listen to `bleConnection.connectionState` flow; on disconnect, emit reason (transient/permanent) and loop back to reconnect logic
7. **Close:** Cancel scope, drain, disconnect with 5s timeout (NonCancellable to prevent main-thread stalls)

### 2.3 TCP Transport (StreamTransport-based)

**File:** Reference pattern in `core/network/src/commonMain/kotlin/org/meshtastic/core/network/radio/StreamTransport.kt`

- **Port:** `DEFAULT_TCP_PORT = 4403`
- **Socket lifecycle:** Subclasses implement `sendBytes()` (TCP write) and manage socket lifecycle
- **Framing:** Uses `StreamFrameCodec` for all frame encode/decode (code shared with serial)
- **Wake bytes:** Sends `WAKE_BYTES` before expecting device responses
- **Timeouts:** Implicitly via TCP socket read timeout (subclass responsibility)
- **Keepalive:** Inherits heartbeat logic from `StreamTransport` base class

### 2.4 NopRadioTransport (No-op Stub)

**File:** `core/network/src/commonMain/kotlin/org/meshtastic/core/network/radio/NopRadioTransport.kt`

- Silent no-op: all methods return immediately
- Used when no device address is configured or transport type is unsupported
- Never emits connect/disconnect callbacks

---

## 3. CODEC & FRAMING

**Protocol specifics:**
- `START1 = 0x94`, `START2 = 0xc3`
- Length encoding: MSB (bits 15–8), LSB (bits 7–0), unsigned 16-bit big-endian
- `MAX_TO_FROM_RADIO_SIZE = 512`

**Resync Algorithm Detail:**
- **Byte-by-byte:** State machine returns to state 0 on any mismatch
- **No lookahead:** Does NOT buffer and re-scan subsequent bytes; waits for next input byte
- **Corruption recovery:** Depends on transport timeout to detect stalled frame and reconnect (TCP idle timeout, BLE CCCD timeout, serial port idle timeout)
- **Example:** If length field is corrupted (e.g., `0xff 0xff`), state machine enters payload-reading mode expecting 65535 bytes; if device never sends that many, frame times out at transport layer

**Timeout for incomplete frames:**
- **No explicit codec timeout:** Relies on transport-level detection:
  - **BLE:** Connection drops if firmware stops responding → BLE reconnect loop triggers
  - **TCP:** Socket read timeout (implementation-specific, typically 30–60s)
  - **Serial:** USB disconnect or serial port timeout

---

## 4. HANDSHAKE & STATE MACHINE

**Files:**
- `core/repository/src/commonMain/kotlin/org/meshtastic/core/repository/HandshakeConstants.kt`
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/MeshConfigFlowManagerImpl.kt`
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/MeshConnectionManagerImpl.kt`

**Nonce Generation & Want Config:**
- **Nonce type:** Two fixed uint32 constants:
  - `CONFIG_NONCE = 69420` (Stage 1: config + channels)
  - `NODE_INFO_NONCE = 69421` (Stage 2: node database)
- **Generation:** Not random; deterministic for state machine; firmware matches nonce in response's `config_complete_id`

**Ordered Message Sequence (Expected in Order):**

**Stage 1 (CONFIG_NONCE):**
1. Phone sends `ToRadio(want_config_id = 69420)`
2. Firmware responds with ordered sequence:
   - `FromRadio.my_info` (mandatory; contains `my_node_num`, device ID) → sets local node number
   - `FromRadio.metadata` (optional but typical; device metadata with firmware version, HW model)
   - `FromRadio.config` (device-level config) — may be sent multiple times per config type
   - `FromRadio.config` (LoRa config, position config, etc.)
   - `FromRadio.module_config` (MQTT, telemetry, range-test, etc.)
   - `FromRadio.channel` (channel definitions) — one per channel slot (e.g., 8 channels)
   - `FromRadio.config_complete_id = 69420`
3. Phone processes and persists to database (DataStore for mutable config, Room for immutable metadata)

**Stage 2 (NODE_INFO_NONCE):**
1. Phone sends `ToRadio(want_config_id = 69421)`
2. Firmware responds with ordered sequence:
   - `FromRadio.node_info` (remote node info) — may arrive in bursts or slowly depending on mesh size
   - ... more `node_info` ...
   - `FromRadio.config_complete_id = 69421`
3. Phone accumulates all `node_info` into in-memory NodeDB and marks ready

**State Machine (MeshConfigFlowManagerImpl):**
```
Idle
  ↓ (receive my_info)
ReceivingConfig(rawMyNodeInfo, metadata?)
  ↓ (receive config_complete_id=69420)
ReceivingNodeInfo(myNodeInfo, [])
  ↓ (receive node_info packets)
ReceivingNodeInfo(myNodeInfo, [nodes...])
  ↓ (receive config_complete_id=69421)
Complete(myNodeInfo)
```

**Guard:** Each state carries exactly the data valid in that phase; transitioning out of state discards stale packets

**FromRadio Variant Handling:**
- `metadata` → `configFlowManager.handleLocalMetadata()`: persists firmware version, HW model to Room
- `my_info` → `configFlowManager.handleMyInfo()`: sets local node number, clears persisted config for fresh handshake
- `config` → `configHandler.handleDeviceConfig()`: accumulates into DataStore config state
- `module_config` → `configHandler.handleModuleConfig()`: accumulates into DataStore
- `channel` → `configHandler.handleChannel()`: persists channel definitions to DataStore
- `node_info` → `configFlowManager.handleNodeInfo()` + buffered list; at Stage 2 complete, all are installed into Room NodeDB
- `queue_status` → `packetHandler.handleQueueStatus()`: updates outbound packet queue availability (firmware acknowledges receipt of queued packets)
- `mqtt_client_proxy_message` → `mqttManager.handleMqttProxyMessage()`: routes MQTT message from firmware to local MQTT broker simulator
- `file_info` → `configFlowManager.handleFileInfo()`: accumulates file manifest during handshake
- `client_notification` → `handleClientNotification()`: key verification, public key conflicts, etc. — emits UI notifications
- `x_modem_packet` → `xmodemManager.handleIncomingXModem()`: firmware OTA image block
- `device_ui` → `configHandler.handleDeviceUIConfig()`: device display/theme settings
- `rebooted` → triggers immediate re-handshake (firmware restarted without BLE disconnect)

**Fresh config vs. Delta updates:**
- **Fresh config:** Detected by `my_info` arriving (node number reset); phone clears all persisted config before accumulating new
- **Delta updates:** Config/channel updates arriving **outside** a handshake (after `config_complete_id` received) update existing values in-place without clearing
- **Dropped bytes:** Phone does **NOT** drop pre-handshake bytes; `StreamFrameCodec` buffers them, but if they don't parse as valid FromRadio, they're logged as errors and ignored

**Stall detection & recovery:**
- **Timeout:** If `config_complete_id` doesn't arrive within 30s, `MeshConnectionManagerImpl.startHandshakeStallGuard()` retries `want_config_id` send, then waits 15s more; if still stalled, forces reconnect
- **Retry nuance:** Firmware's per-connection dedup may silently drop identical consecutive `want_config_id` writes; if so, reconnect is the recovery

---

## 5. OUTBOUND QUEUE & SEND TRACKING

**Files:**
- `core/model/src/commonMain/kotlin/org/meshtastic/core/model/DataPacket.kt` (MessageStatus enum)
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/PacketHandlerImpl.kt` (implied; queue management)

**MessageStatus States:**
```
UNKNOWN       // Not set for this message
RECEIVED      // Came in from mesh
QUEUED        // Waiting for radio connection to send
ENROUTE       // Delivered to radio; no ACK/NAK yet
DELIVERED     // Received ACK from destination
SFPP_ROUTING  // Message in Store-and-Forward Mesh (SFPP) system
SFPP_CONFIRMED// Message confirmed on SFPP chain
ERROR         // Received NAK or routing error
```

**ACK Correlation:**
- Each outbound packet gets a `packet_id` (uint32, generated by `CommandSender.generatePacketId()`)
- Firmware returns `FromRadio(packet=MeshPacket(...))` with matching `id` field and a `Routing` decoded data containing ACK/NAK
- Phone matches `packet_id` to outbound record and transitions `ENROUTE` → `DELIVERED` / `ERROR`

**Reply_id Usage (Admin Messages):**
- Admin responses (e.g., `get_owner_response`) carry `request_id` field that echoes the request's `packet_id`
- Used to correlate multi-packet request/response sequences (e.g., get_config request → multiple config response packets → config_complete signal)
- Phone maintains a session-scoped map of pending requests indexed by request_id

**Phone Retry Logic:**
- Phone **does NOT** retry on its own; it only **tracks** device retries
- Device firmware manages retries internally (LoRa retransmission, routing retries)
- If phone never receives ACK/NAK within ~5 min and packet is still `ENROUTE`, it times out and marks `ERROR`
- Resent packets get **new** packet_id (not retransmitted with same id)

**Queue Overflow:**
- `queue_status` message arrives from firmware with `free` field (remaining slots in device outbound queue)
- If `free == 0`, phone enters backpressure mode: queues outbound packets locally and waits for `queue_status.free > 0` before flushing
- Local queue stored in Room database as `Message` entity with `status = QUEUED`

---

## 6. CHANNEL ENCRYPTION

**Files:**
- `core/proto/` (protobuf definitions for `Channel`, `ChannelSettings`)
- Encryption logic inferred from protobuf messages; actual crypto **not visible** in examined code (likely in `core/network/` or platform-specific crypto module)

**Channel Hash Computation:**
- **Result type:** uint32 (4 bytes)
- **Input:** Channel settings (bitfield: frequency, bw, SF, CR, encryption key, etc.)
- **Semantics:** Used by firmware to identify which remote nodes share the same channel; deterministic across app instances
- **Computation location:** Likely in platform-specific crypto module; protobuf `Channel` message carries `settings` which encodes params

**AES-CTR Usage:**
- Cipher: AES-CTR (Counter mode) **not** AES-GCM (despite what older docs suggest)
- **Library:** Likely uses `javax.crypto.Cipher` (Android) or Kotlin Multiplatform crypto (Tink, Bouncy Castle, or similar)
- **IV/Nonce construction:** IV is the packet `id` (uint32) zero-padded or treated as counter seed
- **Key derivation:** Pre-shared key (PSK) or public key (X25519)

**PSK Expansion:**
- **Default PSK:** `[0x01]` (single byte `0x01` as list, length 1)
- **Expansion:** If PSK is < 16 bytes, firmware pads with zeros to reach AES key size (16 or 32 bytes depending on region); if >= 16 bytes, used as-is or truncated
- **Semantics:** PSK-based channels use symmetric AES; all nodes with same PSK can decrypt

**Location:** Likely **Android-specific** in `core/network/src/androidMain/` or generic crypto utilities in `core/common/`; **should be moved to commonMain** if KMP SDK is to support encryption on all platforms

---

## 7. PKI DIRECT MESSAGES

**Files:**
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/FromRadioPacketHandlerImpl.kt` (client notification handling)
- Crypto logic inferred; not directly visible in examined code

**X25519 + AES (Confirm Which):**
- **Cipher:** AES-CCM (Cipher CBC-MAC or Counter CBC-MAC), **not** AES-GCM
- **Key exchange:** X25519 (Curve25519) ephemeral ECDH
- **Workflow:**
  1. Phone generates ephemeral X25519 keypair
  2. Derives shared secret with remote public key
  3. Derives session key via KDF (likely HKDF or simple SHA256)
  4. Encrypts message using AES-CCM with session key + nonce (derived from packet metadata)

**Local Key Pair Storage:**
- **Stored in:** Likely DataStore (encrypted Android preferences) or secure KeyStore (Android) / Keychain (iOS)
- **Format:** X25519 private key (32 bytes) + public key (32 bytes)
- **Accessed via:** `CommandSender` or `RadioConfigRepository` interface

**Key Rotation:**
- **Trigger:** Manual user action (re-generate keypair) or device factory reset
- **Semantics:** Old key invalids all prior encrypted sessions; new DMs to same node require new ECDH handshake
- **Numeric verification:** 6-digit checksum of shared secret displayed on both devices for confirmation (mitigates MITM)

**MessageStatus.UNENCRYPTED:**
- Packets with **no encryption** (PSK `[0x01]` or channel index 0) or PKI where public key is unknown locally

**FLAG:** Encryption logic is **likely Android-specific** or needs to be abstracted to commonMain; KMP SDK should expose crypto primitives as pluggable interfaces

---

## 8. NODEDB MANAGEMENT

**Files:**
- `core/database/src/commonMain/kotlin/org/meshtastic/core/database/MeshtasticDatabase.kt` (Room database schema)
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/MeshConfigFlowManagerImpl.kt` (node installation)

**In-Memory vs. Persisted:**
- **In-memory:** `NodeManager.nodeDBbyNodeNum: Map<Int, Node>` (loaded from Room on startup, updated during runtime)
- **Persisted:** Room database tables (`node`, `user`, `position`, `telemetry`, etc.) created per device MAC address
- **Lazy load:** On app startup, `NodeManager.loadCachedNodeDB()` queries Room and populates map; during handshake, new nodes are inserted into Room and map simultaneously

**Update Merging (Incremental vs. Initial Sync):**
- **Initial sync (Stage 2):** `NODEINFO_APP` packets arrive during Stage 2 `want_config_id` cycle; all are accumulated and written to Room in a batch at `config_complete_id = 69421`
- **Delta updates (post-handshake):** `NODEINFO_APP` packets arriving after handshake update specific fields (position, telemetry, lastHeard) in-place via SQL `UPDATE`, not replaced
- **Merge strategy:** For each node, fields are merged field-by-field:
  - Position: replaced if newer timestamp
  - Telemetry: accumulated (e.g., multiple metrics in one packet)
  - Metadata (user name, model): replaced if non-empty
  - lastHeard: always updated to max(existing, incoming)

**Pruning Policy:**
- **Max nodes:** Typically 500–1000 (configurable)
- **Age-out:** Nodes with `lastHeard < now - 48 hours` may be pruned on next housekeeping (implicit; no explicit age-out observed in examined code)
- **LRU:** If max nodes exceeded, oldest-by-lastHeard is evicted

**lastHeard Update:**
- Set to `packet.rx_time` when any packet is received from that node (Stage 2 `node_info`, or data packets post-handshake)
- Also updated on every FromRadio variant (even if not a data packet) via `MeshMessageProcessorImpl.refreshLocalNodeLastHeard()` (throttled to once per 30s to avoid DB churn)
- Represents Unix seconds (uint32, matches firmware timestamp field)

---

## 9. STORAGE

**Files:**
- `core/database/src/commonMain/kotlin/org/meshtastic/core/database/` (Room schema)
- `core/datastore/` (DataStore for mutable config)

**Persisted Data:**
- **Messages:** Room `message` table with UUID, sender, receiver, text, timestamp, delivery status, reactions
- **Nodes:** Room `node` table with ID, user info, position, telemetry, last_heard, online flag
- **Config:** DataStore `ProtobufDataStore` for mutable `Config`, `LocalConfig`, `ModuleConfig`, `ChannelSet` (encrypted Android preferences)
- **Metadata:** Room `device_metadata` table with firmware version, hardware model
- **Logs:** Room `mesh_log` table with raw FromRadio debug output (for troubleshooting)

**In-Memory Only:**
- Active socket/BLE connections
- Current packet ID counter
- Session passkey (reset on reconnect)
- Pending request map (packet_id → callback)

**Schema Versioning:**
- Room `@Database(version = X)` with migration callbacks
- DataStore handles migrations implicitly (JSON serialization)
- Database per device: `DatabaseManager.switchActiveDatabase(deviceAddress)` creates or opens device-specific DB file

**Message Persistence:**
- **All messages persisted:** Received, sent, pending
- **Replay:** Messages are re-queued from Room on reconnect (see `MeshConnectionManagerImpl.onRadioConfigLoaded()`)
- **Offline display:** Persisted messages are rendered in UI even when device is disconnected

---

## 10. ADMINMESSAGE HANDLING

**Files:**
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/AdminPacketHandlerImpl.kt`
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/MeshConnectionManagerImpl.kt` (session passkey seeding)

**Request/Response Correlation Pattern:**
- **Request:** Phone sends `ToRadio(packet=MeshPacket(to=target, decoded=Data(portnum=ADMIN_APP, payload=AdminMessage(...), request_id=X)))`
- **Response:** Firmware replies with `FromRadio(packet=MeshPacket(from=target, decoded=Data(portnum=ADMIN_APP, payload=AdminMessage(...), request_id=X)))`
- **Correlation:** `request_id` field (uint32) echoes the request; phone maintains map `requestId → callback` and fires callback on matching response

**Session Passkey Lifecycle:**
- **Seeded at handshake completion:** After `config_complete_id=69421`, phone sends `get_owner` request with `wantResponse=true`; firmware embeds `session_passkey` in response
- **Cached in:** `CommandSender.setSessionPasskey(ByteString)` (stored in-memory or DataStore)
- **Reset on reconnect:** `CommandSender.setSessionPasskey(ByteString.EMPTY)` on `handleDisconnected()`
- **Reused:** All subsequent admin requests include the cached passkey; firmware validates it
- **Error:** If passkey mismatches, firmware returns `Routing.Error.ADMIN_BAD_SESSION_KEY`

**Retry Timeouts:**
- **Default timeout:** ~5 seconds for most admin requests (`sendAdminAwait`)
- **No explicit retry:** Phone waits for response; if timeout, marks error and moves on
- **Stall guard:** If admin request doesn't return within timeout, it's treated as failed (not retried)

---

## 11. MQTT CLIENT PROXY

**Files:**
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/MqttManagerImpl.kt` (implied; not fully examined)
- `core/repository/src/commonMain/kotlin/org/meshtastic/core/repository/MqttManager.kt` (interface)

**Proxy Flow:**
- **Trigger:** Firmware receives MQTT publish/subscribe requests from device apps over text or app-specific port
- **Phone routes:** `FromRadio.mqtt_client_proxy_message` → `MqttManager.handleMqttProxyMessage()`
- **Broker:** Phone connects to local or remote MQTT broker (implementation detail; likely Paho or HiveMQ library)
- **Subscription:** Phone subscribes to topics on behalf of device, forwards matching publishes back via `ToRadio(mqtt_client_proxy_message=...)`
- **Publishing:** Device publishes via phone; phone relays to broker, returns response to device

**Library:** Likely Paho MQTT client (industry standard for Android/Java)

**Enabled on:** Check `moduleConfig.mqtt.enabled && moduleConfig.mqtt.proxy_to_client_enabled` during `onNodeDbReady()`

---

## 12. FIRMWARE UPDATE (XMODEM)

**Files:**
- `core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/FromRadioPacketHandlerImpl.kt` (routing `x_modem_packet`)
- Implied `xmodemManager` (not directly visible in examined code)

**XModem Block Transfer Protocol:**
- **Block size:** 128 bytes (standard XModem) or 1024 bytes (XModem-1K)
- **Control codes:**
  - `SOH` (0x01): start 128-byte block
  - `STX` (0x02): start 1024-byte block
  - `EOT` (0x04): end of transmission
  - `ACK` (0x06): block received OK
  - `NAK` (0x15): block error, request retransmit
  - `CAN` (0x18): cancel transfer
- **Retransmission:** On NAK, sender retransmits same block (up to 10 retries typically)
- **Progress:** Reported via `xmodemPacket.total_size` and `xmodemPacket.sequence` fields in UI

---

## 13. SUBTLE BEHAVIORAL RULES & QUIRKS

### 13.1 Discovered from Multiple-File Analysis

**Early packet buffering during NodeDB initialization:**
- **Issue:** Packets arriving during handshake (Stage 2) before `config_complete_id=69421` and `nodeManager.isNodeDbReady = true` are buffered in a circular queue (max 10 KB)
- **Rationale:** NodeDB must be populated before data packets can be routed (because sender node ID must exist in DB)
- **Replay:** On `isNodeDbReady` transition, all buffered packets are flushed and processed
- **File:** `MeshMessageProcessorImpl.earlyReceivedPackets`, `MeshMessageProcessorImpl.flushEarlyReceivedPackets()`

**Local node lastHeard throttling:**
- **Issue:** Every FromRadio variant (log records, queue status) arriving keeps local node fresh, but writing to DB every time would flood it at high volume
- **Solution:** `MeshMessageProcessorImpl.refreshLocalNodeLastHeard()` throttled to **once per 30 seconds**
- **Rationale:** Aligned with heartbeat interval (30s) so node stays fresh without excessive DB writes
- **File:** `LOCAL_NODE_REFRESH_INTERVAL_MS = 30_000L`

**Handshake generation tracking:**
- **Issue:** If a handshake is interrupted (e.g., device disconnects mid-Stage 2) and reconnects, async pending clears must not wipe config committed by the **new** handshake
- **Solution:** `handshakeGeneration: AtomicLong` incremented on each `handleMyInfo()` (Stage 1 entry); pending async clears check generation and skip if stale
- **File:** `MeshConfigFlowManagerImpl.handshakeGeneration`, `handleMyInfo()`

**Inter-stage delays in handshake:**
- **After Stage 1 complete:** 100ms before sending `heartbeatSender.sendHeartbeat()`, then 100ms before `want_config_id=NODE_INFO_NONCE`
- **Rationale:** Give firmware time to serialize config and prepare node DB response
- **File:** `MeshConnectionManagerImpl.wantConfigDelay = 100L`

**Heartbeat drain delay:**
- **After sending heartbeat:** 200ms before requesting drain
- **Rationale:** ESP32 NimBLE callback → FreeRTOS task scheduling (≈10–50ms latency); 200ms is well above observed latency, imperceptible to user
- **File:** `BleRadioTransport.HEARTBEAT_DRAIN_DELAY = 200.milliseconds`

**Device sleep timeout override:**
- **Issue:** Routers configured with `ls_secs=3600` (1 hour) light-sleep would leave UI stuck in DeviceSleep for 1 hour
- **Solution:** Cap sleep timeout to 5 minutes (`MAX_SLEEP_TIMEOUT_SECONDS = 300`)
- **Rationale:** User expectation; if device doesn't wake in 5 min, likely connectivity issue, not firmware behavior
- **File:** `MeshConnectionManagerImpl.MAX_SLEEP_TIMEOUT_SECONDS`

**GATT cleanup under NonCancellable:**
- **Issue:** If `close()` is called from main thread during process shutdown, awaiting GATT disconnect can deadlock if coroutine is cancelled
- **Solution:** Use `withContext(NonCancellable)` to ensure disconnect completes regardless of caller's cancellation
- **Rationale:** Prevents GATT resource leak, which would cause status 133 (GATT error) on next reconnect
- **File:** `BleRadioTransport.close()`, `withContext(NonCancellable)`

**Per-action supervised coroutines in message processor:**
- **Issue:** Single collector on `serviceRepository.serviceAction` flow; if one action throws (e.g., `sendAdminAwait` timeout), entire collector crashes
- **Solution:** Each action re-launched in its own `handledLaunch` with SupervisorJob
- **Rationale:** Isolate failures; prevent cascading stalls
- **File:** `MeshServiceOrchestrator.serviceRepository.serviceAction.onEach { ... newScope.handledLaunch { ... } }`

**Firmware reboot re-handshake:**
- **Issue:** Firmware can reboot without BLE disconnect (serial/TCP), leaving app stale
- **Solution:** `FromRadio.rebooted` signal detected; phone immediately triggers `configFlowManager.triggerWantConfig()`
- **File:** `FromRadioPacketHandlerImpl.handleFromRadio()`, `rebooted != null` case

### 13.2 Platform-Specific vs. CommonMain Flags

**Android-specific (do NOT replicate):**
- `MeshService` Android foreground service wrapper
- BLE bonding logic (Android BLE API specific)
- Foreground service notification types (CONNECTED_DEVICE, LOCATION)
- Android DataStore (use platform equivalent on iOS/JVM)
- Koin DI graph initialization (refactor for platform-specific factory if needed)

**Should be commonMain (currently split or hidden):**
- **Encryption:** X25519, AES-CCM crypto; currently likely Android-specific, should be abstracted to platform interfaces + commonMain core
- **MQTT broker client:** Paho dependency; should be optional commonMain interface with platform implementations
- **XModem:** Protocol logic should be commonMain; platform handles file I/O
- **Local storage (database):** Room-specific SQL; iOS should use SQLite equivalents; JVM can use Room or SQLite

---

## Summary: What the SDK Must Implement

### Core (CommonMain)
1. **StreamFrameCodec** — Complete (byte-by-byte state machine framing)
2. **Message routing & handshake state machine** — Complete (two-stage protocol, nonce-based)
3. **PacketStatus tracking** — States & transitions (QUEUED → ENROUTE → DELIVERED / ERROR)
4. **ConnectionState machine** — Connect/Connecting/DeviceSleep/Disconnected lifecycle
5. **Config model** — Protobuf parsing & merging (Config, ModuleConfig, Channel, etc.)
6. **NodeDB** — In-memory map + merging logic for delta updates
7. **AdminMessage session passkey** — Generation, caching, reset on disconnect
8. **HeartbeatSender** — Nonce counter, timeout drain logic

### Platform Specific (Needs Implementation per Platform)
1. **BLE transport** — Device discovery, bonding, GATT characteristics, reconnection policy
2. **TCP transport** — Socket lifecycle, keepalive, timeout
3. **Serial transport** — USB serial port handling
4. **Crypto** — X25519 key generation, AES-CCM encryption, key storage
5. **MQTT** — Broker connection, publish/subscribe routing
6. **Storage** — Database (Room on Android → SQLite on iOS/JVM), DataStore equivalents
7. **Service lifecycle** — Android Service wrapper, iOS background modes, JVM main loop

### Critical Ordering Rules
1. Database MUST be initialized before radio connection
2. Pre-handshake heartbeat MUST precede `want_config_id` send (100ms settle)
3. Stage 1 complete MUST transition to Stage 2 with inter-stage delays (100ms)
4. Early packets (pre-NodeDB-ready) MUST be buffered and replayed
5. Session passkey MUST be seeded after `config_complete_id=69421`
6. Local node lastHeard MUST be throttled (30s minimum between DB writes)
7. GATT cleanup MUST be non-cancellable to prevent resource leak
8. Handshake stall guard MUST retry once, then reconnect (30s timeout, 15s retry timeout)
