---

# **Meshtastic Firmware Protocol Behavior Analysis**

## **1. FRAMING (Transport-Agnostic Behavior)**

### **Frame Structure**
- **Start bytes (confirmed):** `0x94` (START1) and `0xC3` (START2) — verify in `src/mesh/StreamAPI.cpp` line 7-8
- **Location:** `/Users/james/StudioProjects/Meshtastic-Android/.agent_refs/firmware/src/mesh/StreamAPI.cpp:7`

```
START1 = 0x94
START2 = 0xC3
```

### **Length Encoding**
- **Format:** Big-endian uint16, 16-bit length after framing bytes
- **Byte order:** High byte then low byte (big-endian)
- **Implementation:** `len = (rxBuf[2] << 8) + rxBuf[3]` — StreamAPI.cpp line ~75
- **Header format:** 4 bytes total: [0x94, 0xC3, len_high, len_low]

### **Maximum Payload Size**
- **Constant name:** `MAX_TO_FROM_RADIO_SIZE`
- **Value:** **512 bytes**
- **Definition location:** `src/mesh/PhoneAPI.h` line 13
- **Rationale:** Static checks in PhoneAPI.h verify that protobuf messages fit within this limit

### **Resync Algorithm (Confirmed)**
When a length byte exceeds max (>512):
1. Device resets `rxPtr = 0` and begins searching forward for next `0x94`
2. State machine: scan for START1 (0x94), then START2 (0xC3)
3. Once 4-byte header complete, validate length ≤ 512
4. If validation fails, reset `rxPtr = 0` and resume scanning forward
5. If length valid but parse fails, continue attempting parse on that packet
6. If all bytes received and parse succeeds, process; otherwise remain in scanning state

**Location:** `src/mesh/StreamAPI.cpp` lines ~70-95 (handleRecStream function)

### **Transport-Specific Usage**
- **Uses framing:** USB-CDC serial (via SerialConsole.cpp), TCP (via WiFiServerAPI/ethServerAPI)
- **Does NOT use framing:** **BLE uses native GATT characteristics without start bytes**
- **Framing rationale:** Prevents confusion with 7-bit ASCII and UTF-8, aids debug output transition

**Locations:**
- TCP: `src/mesh/api/WiFiServerAPI.cpp` and `src/mesh/api/ethServerAPI.cpp`
- Serial: `src/SerialConsole.cpp` extends StreamAPI
- BLE: `src/nimble/NimbleBluetooth.cpp` — no framing, direct characteristic writes

---

## **2. PhoneAPI HANDSHAKE & STATE MACHINE**

### **State Sequence**
The device runs these states in order (from `PhoneAPI.cpp` lines 213-223):
1. **STATE_SEND_NOTHING** — Initial state, no transmission until client requests config
2. **STATE_SEND_MY_INFO** — Device's own node info (MyInfo)
3. **STATE_SEND_UIDATA** — UI configuration data
4. **STATE_SEND_OWN_NODEINFO** — Self as a NodeInfo entry
5. **STATE_SEND_METADATA** — Device metadata (returned by `getDeviceMetadata()`)
6. **STATE_SEND_CHANNELS** — All channel definitions (loops through channels 0 to MAX_NUM_CHANNELS)
7. **STATE_SEND_CONFIG** — All Config sections (device, position, power, network, display, lora, bluetooth, security, sessionkey, device_ui)
8. **STATE_SEND_MODULECONFIG** — All ModuleConfig sections (mqtt, serial, external_notification, store_forward, range_test, telemetry, canned_message, audio, remote_hardware, neighbor_info, detection_sensor, ambient_lighting, paxcounter, traffic_management)
9. **STATE_SEND_OTHER_NODEINFOS** — All other nodes in NodeDB (with prefetching queue to avoid blocking on BLE)
10. **STATE_SEND_FILEMANIFEST** — File manifest entries
11. **STATE_SEND_COMPLETE_ID** — Terminator packet
12. **STATE_SEND_PACKETS** — Normal packet/debug message flow

**Location:** `src/mesh/PhoneAPI.cpp` lines 213-223 and getFromRadio() switch statement (lines 246+)

### **Handshake Trigger & Response**
- **Phone sends:** `ToRadio.want_config_id = N` where N is a unique client-chosen ID
- **Device receives:** Sets `config_nonce = N` and calls `handleStartConfig()`
- **Device response:** Streams the complete config sequence above, terminating with `FromRadio.config_complete_id = N`
- **Special behavior:** If `want_config_id = 0`, treated as normal (no special override)
- **Other special nonces:**
  - `SPECIAL_NONCE_ONLY_CONFIG = 69420` → Skip node info entirely, jump to file manifest
  - `SPECIAL_NONCE_ONLY_NODES = 69421` → Send only node info, skip config sections
  - **Locations:** `src/mesh/PhoneAPI.h` lines 23-24; usage in PhoneAPI.cpp lines 289, 467

### **Packet Dropping During Reconfig**
- **Queued packets:** NOT dropped when new `want_config_id` received
- **Behavior:** Device enters config state machine, queued packets remain in router queue and are sent after config_complete_id
- **Note:** During config handshake (`state != STATE_SEND_PACKETS`), MQTT proxy messages are discarded with warning

**Location:** `src/mesh/PhoneAPI.cpp` lines 179-182

### **want_config_id While Disconnected**
- **Behavior:** `config_nonce` is reset to 0 in `close()` function when connection drops
- **Reconnection:** Next `want_config_id` triggers fresh config sequence
- **Location:** `src/mesh/PhoneAPI.cpp` lines 103-128 (close function)

---

## **3. BLE GATT PROTOCOL**

### **Service & Characteristic UUIDs**
- **Service UUID:** `6ba1b218-15a8-461f-9fa8-5dcae273eafd`
- **Characteristic UUIDs:**
  - **fromradio** (read): `2c55e69e-4993-11ed-b878-0242ac120002`
  - **toradio** (write): `f75c76d2-129e-4dad-a1dd-7866124401e7`
  - **fromnum** (notify/read): `ed9da18c-a800-4f66-a670-aa7547e34453`
  - **logradio** (legacy, notify/read): `6c6fd238-78fa-436b-aacf-15c5be1ef2e2`

**Locations:** `src/BluetoothCommon.h` lines 9-13

### **fromnum Notification Semantics**
- **Value:** 4-byte little-endian counter (fromRadioNum)
- **Semantics:** Increments each time a packet is added to the fromradio queue
- **Phone behavior on notification:** Client drains fromradio characteristic repeatedly via read until receiving 0-byte response
- **Not a message count:** Just a counter to wake the phone's read loop
- **Implementation:** Phone waits for fromnum notify, then repeatedly reads fromradio until empty

**Location:** `src/nimble/NimbleBluetooth.cpp` lines 336-349 (onNowHasData function, puts value in little-endian)

### **Drain Protocol**
- **Phone algorithm:**
  1. Subscribe to fromnum notifications
  2. On fromnum notify, read fromradio characteristic repeatedly
  3. Continue reading until fromradio returns 0 bytes (zero-size read signals end of queue)
  4. Go back to waiting for next fromnum notification
- **Critical:** During config phase, zero-size reads break some clients, so device blocks onRead until data available
- **After config (STATE_SEND_PACKETS):** Zero-size reads are acceptable

**Location:** `src/nimble/NimbleBluetooth.cpp` lines 120-128 (comments in BluetoothPhoneAPI class)

### **MTU Negotiation**
- **Preferred MTU (ESP32-S3/C6):** 517 bytes
- **Default fallback:** 23 bytes (BLE minimum)
- **Device behavior:** Requests preferred MTU on connection
- **TX octets (ESP32-S3/C6):** 251 bytes
- **Characteristics allocated:** Up to 512 bytes for logradio and fromradio on secure connections

**Locations:**
- `src/nimble/NimbleBluetooth.cpp` lines 33-35 (ESP32-S3/C6 constants)
- Lines 832-836 (MTU request)
- Lines 884-895 (characteristic creation with 512-byte max)

### **Bonding/Pairing**
- **Passkey mode:** Numeric comparison with device-displayed 6-digit PIN
- **Fixed passkey option:** Can set `config.bluetooth.fixed_pin` for static pairing
- **Random passkey:** If not fixed, device generates random value 100,000–999,999 (ensures 6 digits)
- **Display:** Shows on device screen (if has display) and logs
- **Security flags:** Characteristics can require WRITE_AUTHEN, WRITE_ENC, READ_AUTHEN, READ_ENC (optional, platform-dependent)
- **NRF52 legacy:** Older NRF52Bluetooth.cpp uses classic BLE pairing (vs NimBLE's default)

**Locations:**
- `src/nimble/NimbleBluetooth.cpp` lines 590-634 (passkey generation and display)
- Lines 887-894 (optional authentication/encryption properties)

### **Device Info Service**
- **Battery Service (0x180F):** Yes, device exposes battery level characteristic (0x2A19)
- **Location:** `src/nimble/NimbleBluetooth.cpp` line 908

### **Nimble vs Legacy BluetoothPhoneAPI**
- **Current:** NimbleBluetooth (async/concurrent-safe with FreeRTOS task synchronization)
- **Legacy:** NRF52Bluetooth (simpler, on NRF52 platform only)
- **Modern SDK should target:** NimbleBluetooth on ESP32, NRF52Bluetooth on NRF52

**Locations:** `src/nimble/NimbleBluetooth.cpp` (current); `src/platform/nrf52/NRF52Bluetooth.cpp` (legacy)

---

## **4. ENCRYPTION**

### **Channel-based Encryption (Symmetric)**
- **Algorithm:** AES-256-CTR (or AES-128-CTR if key is 16 bytes)
- **Key derivation:** PSK (pre-shared key) from channel settings
- **IV/Nonce construction (16 bytes):**
  1. Bytes 0-7: packet_id (uint64, little-endian)
  2. Bytes 8-11: from_node (uint32, little-endian)
  3. Bytes 12-15: zeros (reserved/padding)
- **No encryption case:** PSK length = 0 → packet sent in cleartext

**Locations:**
- Algorithm: `src/mesh/CryptoEngine.cpp` lines 249-272 (encryptAESCtr)
- Nonce init: `src/mesh/CryptoEngine.cpp` lines 287-296 (initNonce function)

### **Default Channel PSK Semantics**
- **PSK size 0:** No encryption
- **PSK size 16:** AES-128
- **PSK size 32:** AES-256 (standard)
- **Channel 0 (primary):** Typically 32 bytes (AES-256)
- **Device doesn't expose "psk = 1 → use default"** — that's client-side abstraction

### **Channel Hash Computation**
- **Formula:** XOR of all channel name bytes XOR'd with all PSK bytes
- **Result range:** 0-255 (single byte)
- **Display format in UI:** "0x41 + (hash % 26)" as letter A-Z (encoded in channel name prefix)
- **Purpose:** Low-quality hint to radio decoder to narrow channel candidates
- **Algorithm:**
  ```
  hash = xorHash(channel_name_bytes, name_len)
  hash ^= xorHash(psk_bytes, psk_len)
  ```

**Location:** `src/mesh/Channels.cpp` lines 27-51 (generateHash and xorHash functions)

### **PKI Direct Messages (X25519 ECDH)**
- **Key agreement:** Curve25519 ECDH between sender's private key and receiver's public key
- **Encryption after ECDH:** AES-256-CCM (not CTR; CCM provides authenticated encryption)
- **Public key storage:**
  - User.public_key (32 bytes, from NodeDB)
  - MeshPacket.public_key (optional override for direct message encryption)
- **Session key derivation:** ECDH result is SHA256-hashed to produce 32-byte shared key
- **Authentication tag:** 8 bytes appended by AES-CCM
- **Nonce for CCM:** Same as symmetric (packet_id + from_node), but only 8 bytes used
- **Extra nonce:** 4-byte random value appended after auth tag (extraNonce stored at auth+8)

**Locations:**
- `src/mesh/CryptoEngine.cpp` lines 106-140 (encryptCurve25519)
- `src/mesh/CryptoEngine.cpp` lines 154-178 (decryptCurve25519)
- CCM library call: line 137 (`aes_ccm_ae`)

### **Key Verification Numeric Flow**
- **Not implemented** in firmware as a user-facing feature
- **Passkey display is for BLE pairing only,** not packet verification
- **Assumption:** Clients verify Curve25519 keys via out-of-band means if needed

---

## **5. ROUTING & ACK SEMANTICS**

### **hop_limit & hop_start Initialization**
- **On send:** `p->hop_limit = Default::getConfiguredOrDefaultHopLimit(config.lora.hop_limit)`
- **Fallback:** Config value or device default (typically 3)
- **hop_start:** Set equal to hop_limit at packet transmission, used to calculate hops_away on receive
- **Decrement logic:** First hop always decrements (prevents retry confusion); subsequent hops may skip decrement if both local and previous relay are routers

**Locations:** `src/mesh/Router.cpp` lines 215, 291, 360 (hop initialization and start assignment)

### **want_ack Semantics**
- **Sender sets:** `p->want_ack = true` to request acknowledgment
- **Router/relay response:** On receiving a packet with want_ack=true, generates a Routing-app ACK
- **ACK message fields:**
  - Packet ID (request_id field, echoing original)
  - From/To reversed (ack comes from destination back to source)
  - PortNum: ROUTING_APP (meshtastic_PortNum_ROUTING_APP)
- **Implicit ACK:** If a relay forwards the packet, the original sender receives notification (relay is implicit ack)
- **Explicit ACK:** From final destination via Routing app
- **Retry responsibility:** **Device handles retries internally; SDK should NOT retry**

**Locations:**
- `src/modules/RoutingModule.cpp` line 86 (ROUTING_APP port)
- `src/mesh/Router.cpp` lines 290-291, 349-351 (want_ack handling and broadcast override)

### **Retry Timer & Attempts**
- **ReliableRouter:** Implements exponential backoff for retries (details in NextHopRouter)
- **Retry count:** Implementation-specific; device retries several times before giving up
- **Device responsibility:** Does NOT expect phone to retry; phone receives final status
- **Note:** Exact retry timing extracted from NextHopRouter.h, but specific constants need deeper inspection

**Location:** `src/mesh/ReliableRouter.h` and `src/mesh/NextHopRouter.h` (headers indicate retry logic exists)

### **request_id / reply_id Semantics**
- **AdminMessage replies:** Use request_id to correlate response to request
- **Routing module:** Uses packet ID in request, echoes in ack via request_id field
- **App-level:** Modules track request IDs to match replies (timeout if no reply within timeout window)

**Location:** `src/modules/AdminModule.cpp` (multiple admin message handlers check/echo request IDs)

---

## **6. SERIAL TRANSPORT**

### **Baud Rate**
- **Standard:** 115200 bps
- **Data bits:** 8
- **Parity:** None (N)
- **Stop bits:** 1
- **Flow control:** None (handled by USB CDC)

**Location:** `src/DebugConfiguration.h` line 23; `src/SerialConsole.cpp` line 67 (Port.begin(SERIAL_BAUD))

### **Framing Identity with TCP**
- **IDENTICAL:** Serial uses same 0x94 0xC3 framing as TCP
- **Rationale:** Allows clean transition from debug text output to protobuf mode on same port

---

## **7. TCP TRANSPORT**

### **Default Port**
- **Port:** 4403 (meshtastic standard)
- **Definition:** `src/mesh/api/ServerAPI.h` line 6 (`SERVER_API_DEFAULT_PORT`)

### **Concurrent Connections**
- **Currently:** Only ONE concurrent phone TCP connection allowed
- **Implementation:** Device enforces single connection; second connection attempt waits/fails
- **Note:** Comment in ServerAPI.h line 43 indicates this is a future enhancement

**Locations:** `src/mesh/api/ServerAPI.cpp` lines 34-87 (connection timeout and limit logic)

### **Connection-Close Semantics**
- **TCP disconnect:** Triggers `close()` in ServerAPI/PhoneAPI
- **want_config_id:** Reset to 0 in close()
- **Session state:** Completely cleared (state = STATE_SEND_NOTHING)
- **Reconnection:** Next connection starts fresh config handshake

**Location:** `src/mesh/PhoneAPI.cpp` lines 103-128 (close function)

---

## **8. MQTT PROXY MODE**

### **Proxy Activation**
- **Enabled when:** `moduleConfig.mqtt.proxy_to_client_enabled = true`
- **Condition:** At least one channel has MQTT enabled OR map reporting enabled
- **Direction:** Device receives MQTT messages and forwards to phone via MqttClientProxyMessage; phone sends MqttClientProxyMessage to device which forwards to MQTT broker

### **Topic Format**
- **Schema:** `msh/2/e/{channel_id}/{node_id}`
- **Channel ID:** Hash of channel name
- **Node ID:** Hex node number
- **Crypt topic prefix:** `/2/e/` (indicates encrypted channel traffic)

**Location:** `src/mqtt/MQTT.h` line 104 (cryptTopic definition)

### **Payload Encoding**
- **MQTT text messages:** Forwarded as-is (string)
- **MQTT binary data:** Forwarded as-is (byte array)
- **Protobuf:** Packets are protobuf-encoded on MQTT side, decoded for phone

**Locations:** `src/mqtt/MQTT.cpp` lines 483-530 (onClientProxyReceive and payload forwarding)

### **Retained Messages**
- **Behavior:** Not explicitly managed by firmware proxy; MQTT broker handles retention
- **Note:** Device does not set/clear retained flag in proxy; passes through as-is

---

## **9. ADMIN MESSAGE ROUTING & SESSION PASSKEY**

### **Session Passkey Mechanism**
- **Returned by:** `getDeviceMetadata()` in AdminModule
- **Size:** 8 bytes (uint8_t[8])
- **Generation:** Random bytes on each `get_device_metadata_request`
- **Lifetime:** ~300 seconds (5 minutes); regenerated halfway through expiry to provide sliding window
- **Validation:** Checked on state-changing admin messages; incorrect passkey → message rejected

**Locations:**
- `src/modules/AdminModule.cpp` lines 1464-1480 (passkey generation and validation)
- `src/modules/AdminModule.cpp` line 1282 (getDeviceMetadata return)

### **Channel for Admin Messages**
- **Primary channel:** admin channel (special internal channel, typically channel 0 or designated admin channel)
- **Can also:** Travel on any channel, but response uses primary
- **Note:** AdminModule inherits from ProtobufModule; specific channel is configurable

**Location:** `src/modules/AdminModule.cpp` line 86 (module initialization with channel parameter)

### **begin_edit_settings / commit_edit_settings**
- **Semantics:**
  - **begin_edit_settings:** Lock configuration for exclusive edit by requesting client; other clients cannot modify until commit or timeout
  - **commit_edit_settings:** Apply pending changes and unlock
- **Transaction:** Multiple config changes bundled between begin/commit
- **Timeout:** Automatically unlock if no commit received within timeout window

**Location:** `src/modules/AdminModule.cpp` lines 357-367 (handlers for these messages)

---

## **10. MISCELLANEOUS PROTOCOL BEHAVIORS**

### **Packet Deduplication via packet_id**
- **Mechanism:** Router maintains PacketHistory tracking recent packet IDs
- **Purpose:** Prevent relay loops and duplicate processing
- **Lifecycle:** Packet IDs retained for a time window (exact TTL in PacketHistory.h)
- **Note:** Device drops duplicate packets silently

**Location:** `src/mesh/Router.h` and `src/mesh/Router.cpp` (PacketHistory base class)

### **Queue Overflow Behavior**
- **RX queue:** Max 4 packets from radio (MAX_RX_FROMRADIO)
- **TX queue:** Configurable; if full, new packets are dropped or cause backpressure
- **Phone-to-radio queue:** Size 3 (NIMBLE_BLUETOOTH_FROM_PHONE_QUEUE_SIZE)
- **Behavior:** Device logs warning/error and continues; packets are not retried by device

**Locations:**
- `src/mesh/Router.cpp` line 30 (MAX_RX_FROMRADIO)
- `src/nimble/NimbleBluetooth.cpp` lines 44-45 (NIMBLE queue sizes)

### **Device-side Prioritization**
- **Config packets (during handshake):** Highest priority, sent immediately via state machine
- **Heartbeat response:** Reply with queue status if heartbeat received
- **Normal packets:** Lowest priority, sent after config complete
- **Implementation:** PhoneAPI.getFromRadio() enforces ordering via state machine

**Location:** `src/mesh/PhoneAPI.cpp` lines 227-237 (heartbeat handling priority)

### **Low-Power Deferred Sends**
- **Not implemented in standard firmware** — all sends are immediate
- **Exception:** Bluetooth may batch sends due to connection parameters
- **Note:** Device does not defer MQTT publishes; sends as soon as radio ready

### **NodeDB Pruning Rules**
- **Nodes retained:** All received node info is retained until NodeDB capacity reached
- **Pruning:** LRU (least recently used) nodes are dropped when capacity exceeded
- **Max nodes:** Configurable, typically 50-100 nodes
- **Note:** Device sends all known nodes during config handshake in STATE_SEND_OTHER_NODEINFOS

### **Time Synchronization & RTC**
- **Device has RTC:** Only if GPS module active or time set via admin message
- **Position.time field:** Set by phone to device's estimate of current UTC time (seconds since epoch)
- **Device behavior:** If no valid time, Position.time remains 0 or device's best estimate
- **Phone responsibility:** Provide current time stamp when uploading position
- **Note:** Device uses RTCQualityDevice quality flag to indicate confidence in time

**Locations:**
- `src/mesh/PhoneAPI.cpp` lines 650, 755 (time field population)
- `src/RTC.h` (RTC quality definitions)

### **Packet Sequence Guarantees**
- **No guaranteed ordering:** Mesh is best-effort; packets may arrive out of order
- **Phone responsibility:** Reorder based on packet timestamps/sequence numbers if needed
- **Exception:** Config handshake (STATE_SEND_*) is strictly ordered

### **Connection Timeout**
- **Serial API timeout:** 15 minutes (900 seconds) of inactivity
- **TCP API timeout:** 15 minutes (900 seconds) of inactivity
- **Timeout constant:** `SERIAL_CONNECTION_TIMEOUT` / `TCP_IDLE_TIMEOUT_MS`

**Locations:**
- `src/SerialConsole.cpp` (comment indicates 15 min timeout)
- `src/mesh/api/ServerAPI.cpp` line 6 (TCP_IDLE_TIMEOUT_MS = 15 * 60 * 1000UL)

---

## **SUMMARY OF KEY CONSTANTS & VALUES**

| Constant Name | Value | File Path | Line |
|---|---|---|---|
| START1 | 0x94 | src/mesh/StreamAPI.cpp | 7 |
| START2 | 0xC3 | src/mesh/StreamAPI.cpp | 8 |
| MAX_TO_FROM_RADIO_SIZE | 512 | src/mesh/PhoneAPI.h | 13 |
| SPECIAL_NONCE_ONLY_CONFIG | 69420 | src/mesh/PhoneAPI.h | 23 |
| SPECIAL_NONCE_ONLY_NODES | 69421 | src/mesh/PhoneAPI.h | 24 |
| SERIAL_BAUD | 115200 | src/DebugConfiguration.h | 23 |
| SERVER_API_DEFAULT_PORT | 4403 | src/mesh/api/ServerAPI.h | 6 |
| TCP_IDLE_TIMEOUT_MS | 900000 | src/mesh/api/ServerAPI.cpp | 6 |
| MESH_SERVICE_UUID | 6ba1b218-15a8-461f-9fa8-5dcae273eafd | src/BluetoothCommon.h | 9 |
| TORADIO_UUID | f75c76d2-129e-4dad-a1dd-7866124401e7 | src/BluetoothCommon.h | 11 |
| FROMRADIO_UUID | 2c55e69e-4993-11ed-b878-0242ac120002 | src/BluetoothCommon.h | 12 |
| FROMNUM_UUID | ed9da18c-a800-4f66-a670-aa7547e34453 | src/BluetoothCommon.h | 13 |

---

## **AMBIGUITIES & ITEMS NEEDING CLARIFICATION**

1. **Exact retry count & backoff timing** for ReliableRouter — NextHopRouter.h indicates logic exists but exact constants not visible in available excerpts
2. **NodeDB pruning policy details** — exact LRU implementation and capacity thresholds
3. **MQTT topic format for encrypted vs. unencrypted channels** — whether `/2/e/` changes or has variants
4. **Admin message transaction timeout** — exact duration for begin_edit/commit_edit timeout
5. **BLE connection parameter switching timing** — exact point when device switches from high-throughput to low-power params (STATE_SEND_COMPLETE_ID vs. STATE_SEND_PACKETS)
6. **Packet deduplication TTL** — how long PacketHistory retains packet IDs before expiry
7. **Multiple app-level request/response correlation** — whether timeout is per-request or global

---

This report describes **observable protocol behavior from the firmware source** without copying code verbatim. All protocol values are derivable from the protobuf schema and documented constants in the source files.
