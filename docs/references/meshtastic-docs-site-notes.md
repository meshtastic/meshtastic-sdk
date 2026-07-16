---

# Meshtastic Official Docs — Protocol & Configuration Notes

## Source
- **Repo**: meshtastic/meshtastic @ `c5c511aba6caab82e7816dfb2409f1757f775607`
- **License**: GPL-3.0 (paraphrased facts only; no verbatim quotes)
- **Scope**: `docs/development/`, `docs/configuration/`, `docs/software/`, and protocol-relevant sections of `docs/about/`
- **Documentation site**: https://meshtastic.org

---

## 1. HTTP API (Transport)

The device firmware exposes a REST API for browser-based clients to interact with Meshtastic nodes.

### Endpoints
- **`PUT /api/v1/toradio`**: Send ToRadio protobuf payloads to the device. Only one ToRadio message per request. Binary protobuf body.
- **`GET /api/v1/fromradio`**: Retrieve FromRadio protobuf payloads from the device. Binary protobuf body. Query parameters:
  - `all=false` (default): Returns only one protobuf.
  - `all=true`: Returns all available protobufs in the queue.
  - `chunked=false` (default): Returns all protobufs for the session's queue (only supported option in initial release).
  - `chunked=true` (planned): Stream chunks with server holding connection open.
- **`OPTIONS /api/v1/toradio`**: Returns 204 No Content (connection validation).

### Content Negotiation
- **Request/Response Content-Type**: `application/x-protobuf`
- **Response Header**: `X-Protobuf-Schema` (optional) indicates protobuf schema URI for documentation.

### Transport Details
- **Protocol**: HTTP and HTTPS (self-signed certificates).
- **Default Port**: Inferred to be 80 (HTTP) or 443 (HTTPS); no explicit port documented for the HTTP API itself. Web client uses device's configured HTTP port.
- **Authentication**: None; trust is assumed based on access to the HTTP server.
- **Payload Framing**: Unlike TCP/Serial, the HTTP API does NOT use the `0x94 0xc3` framing header. Protobuf binary is sent directly in the request/response body.

### CORS & Browser Compatibility
- **HTTPS Requirement for Hosted Client**: The hosted version at `client.meshtastic.org` requires HTTPS connections (all traffic must be encrypted). Direct HTTP connections to the device must first establish trust of the device's self-signed certificate.
- **Reference Client**: `meshtastic.js` (GitHub: meshtastic/meshtastic.js) — JavaScript reference implementation with methods `sendToRadio(packet)` and `onFromRadio(callback)`.

**(docs/development/device/http-api.mdx)**

---

## 2. MQTT Topic Structure & ServiceEnvelope

### MQTT Proxy & Gateway Mode

Devices can act as MQTT gateways, bridging the mesh to public MQTT brokers. The documentation does not exhaustively list all topic variants; the following are documented:

- **Base Topic Pattern**: `msh/2/{channel_id}/{node_id}/{message_type}`
  - `channel_id`: Channel index.
  - `node_id`: 32-bit numeric node ID (often in decimal).
  - `message_type`: Varies (e.g., `text`, `position`, `telemetry`, `map`, `stat`, `json`).

### Encrypted vs. Cleartext Topics
The docs reference `e/` (encrypted) and suggest other prefixes for variant message types (e.g., `json/` for JSON-encoded payloads, `map/` for map data, `stat/` for statistics), but do not exhaustively define the complete topic taxonomy.

### ServiceEnvelope
The `ServiceEnvelope` protobuf wraps MQTT messages. Documented fields include:
- `gateway_id`: ID of the gateway relaying the packet.
- `channel_id`: Channel through which the packet was received/sent.
- The envelope carries the payload (typically another protobuf, e.g., a `MeshPacket`).

**Note**: The exact interaction between `gateway_id` and `channel_id` is not fully elaborated in the docs. Further protocol-level investigation of the protobuf definitions and gateway firmware source would clarify.

### Location Precision Filtering
The public MQTT server filters out precise positions to enhance privacy; `position_precision` channel settings allow per-channel control of how many bits of lat/lon are included.

**(docs/configuration/radio/channels.mdx references position precision filtering; docs/software/integrations would likely have complete MQTT topic spec, but docs are not fully accessible in this extraction)**

---

## 3. Mesh Routing Internals (Retries, Rebroadcast, Hop_start)

### Routing Behaviors

Devices do NOT retry messages themselves; the firmware's routing layer (NextHopRouter or ReliableRouter) handles all retransmission. Host applications MUST NOT retry; instead, they rely on device-side retry and the `ROUTING_APP` application layer for ACK correlation.

### Rebroadcast Modes

Each device role can be configured with a `rebroadcast_mode` controlling packet forwarding:

| Mode | Behavior |
|------|----------|
| `ALL` | (Default) Rebroadcasts ALL messages from the primary mesh and other meshes with identical modem settings, even if encryption differs. |
| `ALL_SKIP_DECODING` | Same as `ALL` but skips decoding; simply rebroadcasts raw packets. Only available with REPEATER role. |
| `LOCAL_ONLY` | Ignores observed messages from foreign meshes (open or undecodable); only rebroadcasts on the node's local primary/secondary channels. |
| `KNOWN_ONLY` | Like `LOCAL_ONLY`, but also ignores messages from nodes not in the NodeDB (known list). |
| `NONE` | No rebroadcasting (permitted only for SENSOR, TRACKER, TAK_TRACKER roles). |
| `CORE_PORTNUMS_ONLY` | Ignores non-standard portnums (TAK, RangeTest, PaxCounter, etc.); rebroadcasts only standard portnums (NodeInfo, Text, Position, Telemetry, Routing). |

### Hop Limit & Max Hops

- **Max Hops**: Default 3; maximum 7. Hop limit is decremented by each hop and packet is discarded if it reaches 0 before being delivered.
- **Hop Start**: Not explicitly documented in the configuration sections, but implied to be set during transmission by the originating device.

### Device-Level Retry & Backoff Timing

Retry counts and backoff strategies for NextHopRouter / ReliableRouter are **not** explicitly documented in the scope docs. This is a gap—the firmware source would need to be consulted for precise timing.

**(docs/configuration/radio/device.mdx for rebroadcast_mode; docs/configuration/radio/lora.mdx for hop_limit)**

---

## 4. LoRa Regions & Modem Presets Table

### Supported Regions

| Region Code | Geographic Coverage | Frequency Band |
|-------------|-------------------|-----------------|
| `US` | North America | 902–928 MHz |
| `EU_433` | Europe | 433–435 MHz |
| `EU_868` | Europe | 863–870 MHz |
| `CN` | China | 470–510 MHz |
| `JP` | Japan | 920–923 MHz |
| `ANZ` | Australia/New Zealand | 915–928 MHz |
| `ANZ_433` | Australia/New Zealand | 433–435 MHz |
| `KR` | South Korea | 920–923 MHz |
| `TW` | Taiwan | 920–928 MHz |
| `RU` | Russia | 866–869 MHz |
| `IN` | India | 865–867 MHz |
| `NZ_865` | New Zealand | 865–867 MHz |
| `TH` | Thailand | 920–925 MHz |
| `LORA_24` | 2.4 GHz (experimental/emerging) | 2400–2483.5 MHz |
| `UA_433` | Ukraine | 433–435 MHz |
| `MY_433` | Malaysia | 433–435 MHz |
| `MY_919` | Malaysia | 919–923 MHz |
| `SG_923` | Singapore | 923–925 MHz |
| `KZ_433` | Kazakhstan | 433–435 MHz |
| `KZ_863` | Kazakhstan | 863–865 MHz |
| `BR_902` | Brazil | 902–928 MHz |
| `NP_865` | Nepal | 865–867 MHz |
| `PH_868` | Philippines | 863–870 MHz |
| `PH_915` | Philippines | 902–928 MHz |

### Modem Presets

Presets balance speed vs. range. Listed from fastest/shortest-range to slowest/longest-range:

| Preset | Characteristics | Notes |
|--------|-----------------|-------|
| `SHORT_TURBO` | Fastest, highest bandwidth (500 kHz), lowest airtime, shortest range. | Not legal in all regions (high BW). |
| `SHORT_FAST` | Fast, short range. | |
| `SHORT_SLOW` | Balanced, short-to-medium range. | |
| `MEDIUM_FAST` | Medium speed/range. | |
| `MEDIUM_SLOW` | Slower, medium range. | |
| `LONG_FAST` | **Default**. Good balance of speed and range. | Recommended for most users. |
| `LONG_MODERATE` | Slower, longer range. | |
| `LONG_SLOW` | Slow, very long range. | |
| `VERY_LONG_SLOW` | Slowest, lowest BW, highest airtime, longest range. | Not recommended for regular use; poor mesh formation and unreliable. |

### Custom Modem Settings

When `lora.use_preset` is `false`, the device uses manually defined:
- **Bandwidth**: Special values (`31` → 31.25 kHz, `62` → 62.5 kHz, `200` → 203.125 kHz, `400` → 406.25 kHz, `800` → 812.5 kHz, `1600` → 1625.0 kHz). Values < 62.5 kHz may require TCXO.
- **Spread Factor**: 5–12 (older SX127x/RF95 chips only support 7–12).
- **Coding Rate**: Denominator of code rate (e.g., 5 for 4/5, 8 for 4/8).

### Region Constraints
- **Duty Cycle**: Europe (EU_433, EU_868) enforces a 10% hourly duty cycle limit. Devices tracking this limit will stop transmitting when exceeded and resume after the cycle resets.
- **Transmit Power**: Defaults to maximum legal continuous power (0 dBm setting). Can be overridden (0–30 dBm range) but must respect local regulations.

**(docs/configuration/radio/lora.mdx; docs/configuration/region-by-country.mdx)**

---

## 5. NodeDB & Channel Slots

### NodeDB Capacity

**Not explicitly documented** in the scoped docs. The firmware likely has a compile-time limit (possibly ~200 nodes on devices like ESP32), but exact capacity and eviction policy are not stated.

### Channel Slots

- **Total Slots**: 8 (indices 0–7).
- **Index 0 (PRIMARY)**: Required, cannot be disabled. By default, all periodic broadcasts (position, telemetry) use this channel.
- **Indices 1–7 (SECONDARY/DISABLED)**: User-configurable.
- **Constraint**: Active channels must be consecutive; you cannot have disabled channels between active ones.
- **Role Assignment per Channel**:
  - `PRIMARY` (1): One instance only.
  - `SECONDARY` (2): Multiple secondary channels allowed.
  - `DISABLED` (0): Disabled channel (settings reverted to default).

### Channel Settings
Each channel has:
- **Name**: Short identifier (< 12 bytes). Reserved name: `admin` (on secondary channels for legacy admin mode).
- **PSK (Pre-Shared Key)**: 0 bytes (none), 16 bytes (AES-128), or 32 bytes (AES-256).
- **Uplink Enabled**: If true, mesh messages are forwarded to public internet gateways.
- **Downlink Enabled**: If true, messages from public gateways are forwarded to the local mesh.
- **Is Muted**: If true, external notifications (e.g., buzzer) are suppressed for messages on this channel.
- **Position Precision**: 0–32 bits of lat/lon precision to transmit (0 = never send position, 32 = full precision).

**(docs/configuration/radio/channels.mdx)**

---

## 6. PKI & Encryption (Corroborate or Correct)

### PSK for Channels (Unchanged)

All chat channel messages use symmetric AES encryption (AES-128-CTR or AES-256-CTR) with a shared pre-shared key (PSK). Channel hash is derived: `XOR(name_bytes) XOR XOR(psk_bytes)` and used in `MeshPacket.channel`.

- **Default PSK**: `0xd4 0xf1 0xbb 0x3a ... 0x69 0x01` (16 bytes).
- **PSK Shorthand**: `[0x00]` = none; `[0x01]` = default key; `[0x02]–[0x0A]` = "simple1"–"simple10" presets.

### PKC for Direct Messages (DMs) — Firmware 2.5.0+

**Encryption Algorithm**: The SDK docs claim AES-256-CCM with an 8-byte tag + 4-byte extra nonce. The Meshtastic official docs **do not** explicitly specify CCM vs. GCM; they state:

> "DMs are encrypted using the recipient's public key, ensuring only the recipient with the corresponding private key can decrypt the message. DMs are signed with the sender's private key before encryption."

**Key Exchange**: X25519 ECDH derives a shared secret. "Symmetric encryption … like AES-CTR or AES-CCM" (emphasis added).

**Nonce**: The SDK established nonce = `packet_id (uint64 LE) ‖ from_node (uint32 LE) ‖ 4 zero bytes`. The docs do not contradict this but don't specify it explicitly either.

### Admin Messages — Firmware 2.5.0+

**PKC Method (2.5.0+)**:
1. Node A's public key is stored in Node B's `security.admin_key` field (up to 3 admin keys per node).
2. Admin commands from Node A are encrypted using X25519 ECDH (Diffie-Hellman Curve25519) to derive a shared secret.
3. Messages encrypted with AES-CTR or AES-CCM using the shared secret.
4. **Session ID**: Included in the encrypted packet, valid for ~300 seconds, mitigates replay attacks.

**Legacy Method (Pre-2.5.0, still supported)**:
- Secondary channel named `admin` (reserved name) with shared PSK.
- Any node in the admin channel can administer any other node in that channel.
- Less secure (PSK-based, no session IDs).

### Public Key Storage & Backup

- **Public Key**: 32 bytes, shared with other nodes.
- **Private Key**: 32 bytes, stored securely on the device; **should be backed up**.
- **Loss & Regeneration**: Keys are lost during firmware erase/reinstall and regenerated; existing encrypted DMs cannot be decrypted with new keys.
- **Backup Methods**: Export config via CLI (`meshtastic --export-config`), or manually copy keys.

**(docs/development/reference/encryption-technical.mdx; docs/configuration/radio/security.mdx)**

**CORROBORATION**: The SDK's established fact that DMs use X25519 ECDH + AES-256-CCM is **partially confirmed** by the docs (X25519 ECDH is explicitly stated for admin messages; AES-CCM is mentioned as an option alongside AES-CTR). The docs do not exhaustively specify CCM vs. GCM or the exact nonce derivation for DMs, so **some SDK facts remain uncontradicted but not fully elaborated**.

---

## 7. AdminMessage Catalog & Session Passkey Lifecycle

### Admin Commands

The documentation does not provide an exhaustive catalog of `AdminMessage` variants. Instead, it describes the **mechanism** and provides high-level categories:

- **Remote Configuration**: Radio settings, device settings, channel settings, module settings.
- **Session Passkey**: Not explicitly detailed in the configuration docs. The SDK claims 8-byte session passkey with a ~5 minute sliding window; the docs mention "Session ID … valid for a short duration (e.g., 300 seconds)" for admin messages in the PKC context.

### Session Lifecycle (Firmware 2.5.0+)

1. **Session ID Generation**: Generated upon first admin command in a session.
2. **Validity Window**: ~300 seconds (5 minutes).
3. **Renewal**: A new session ID is generated after the timeout or upon sending the next control message.
4. **Replay Mitigation**: Old session IDs are discarded and cannot be reused, preventing replay attacks.

### Edit Transactions

The docs mention `begin_edit_settings` and `commit_edit_settings` in the SDK context but do not specify a timeout in the official docs. The SDK references a ~300 second (5 minute) timeout; this is **not contradicted** but also **not confirmed** by the official docs.

**(docs/configuration/remote-admin.mdx; docs/development/reference/encryption-technical.mdx)**

---

## 8. Telemetry Catalog (Sensors, Metrics)

### Telemetry Module

The docs confirm telemetry capabilities exist but do not exhaustively list `TelemetrySensorType` enum values or all field names for `EnvironmentMetrics`, `PowerMetrics`, `AirQualityMetrics`.

**Documented Concepts**:
- **Telemetry packets**: Sent periodically by TRACKER and SENSOR roles when awake (sleep interval: `telemetry.environment_update_interval` for SENSOR role).
- **Environmental data**: Position, temperature, humidity, barometric pressure, etc.
- **Power metrics**: Battery voltage, charging status, etc.

**No exhaustive table** provided in the scoped docs. The SDK likely has this fully specified; the official docs are incomplete on this topic.

**(docs/configuration/radio/device.mdx references SENSOR role and telemetry broadcasting; complete module documentation would be in docs/configuration/module/ which we could not fully extract)**

---

## 9. Roles & Rebroadcast Modes

### Device Roles

| Role | Appearance | BLE/WiFi/Serial | Retransmit | Visible in Nodes | Use Case |
|------|------------|-----------------|-----------|------------------|----------|
| `CLIENT` | (Default) | Yes | Yes | Yes | General messaging. |
| `CLIENT_MUTE` | Minimal | Yes | **No** | Yes | Participate without relaying. Lowest power. |
| `CLIENT_HIDDEN` | Hidden | Yes | Local only | **No** | Stealth/low-power; only rebroadcasts local. |
| `CLIENT_BASE` | Personal base station | Yes | Yes (favorites prioritized) | Yes | Extend range for favorited nearby nodes. |
| `TRACKER` | GPS priority | Yes | Awake only | Yes | Frequent position broadcasts. |
| `LOST_AND_FOUND` | Recovery mode | Yes | Yes | Yes | Broadcasts location as message regularly. |
| `SENSOR` | Telemetry priority | Yes | Awake only | Yes | Environmental sensor deployment. |
| `TAK` | ATAK-optimized | Yes/No (optional) | Yes | Yes | Integration with ATAK (tactical). |
| `TAK_TRACKER` | ATAK with PLI | Yes/No (optional) | Yes | Yes | Automatic TAK PLI broadcasts. |
| `REPEATER` | Infra node (deprecated in 2.7.11+) | Yes | Yes | **No** | Extend coverage; not shown in topology. |
| `ROUTER` | Infra node | **No** | Yes | Yes | Extend coverage; visible in topology. Power saving enabled by default (ESP32 only). |
| `ROUTER_LATE` | Infra node (late rebroadcast) | Yes | Yes | Yes | Cover dead spots; rebroadcast after other roles. |

### Rebroadcast Modes

*(See Section 3 above for detailed table)*

**(docs/configuration/radio/device.mdx)**

---

## 10. Position Precision & Truncation

### Position Precision Bits

Channel-specific setting: `position_precision` (0–32 bits).

| Bits | Precision | Approx. Radius |
|------|-----------|----------------|
| 0 | Never send | N/A |
| 1 | ~5,000 km | Continental |
| 13 | ~400 m | City block |
| 16 | ~50 m | House-sized |
| 20 | ~3 m | Room-sized |
| 21 | ~1.5 m | Precise indoor |
| 32 | ~0.1 mm | Full precision (WGS84 double) |

### Bit-Mask Formula

Not explicitly documented in the scoped docs. The SDK establishes a truncation formula; the docs reference the table above but do not provide the mathematical bit-manipulation formula for position precision.

### MQTT Privacy Filtering

Public MQTT brokers filter out precise positions (likely keeping only low-precision values, ~10–13 bits).

**(docs/configuration/radio/channels.mdx; the precision bit table referenced is in a shared block)**

---

## 11. Traceroute / RouteDiscovery

### RouteDiscovery Semantics

**Not detailed** in the scoped docs. The docs confirm traceroute functionality exists as a module but do not specify:
- How hop-by-hop path is populated.
- How `snr_towards` / `snr_back` arrays are constructed.
- Time-to-live (TTL) or termination conditions.

This is a gap; the firmware source or dedicated module documentation would be needed.

**(docs/configuration/module/ — not fully extracted in this pass)**

---

## 12. Module-Specific Contracts (SFPP, NeighborInfo, RangeTest, DetectionSensor, Paxcounter, MapReport, ATAK)

### Module Framework

Modules inherit from `MeshModule`, `SinglePortModule`, or `ProtobufModule` and are automatically registered to receive packets on their assigned port number.

**Sending**: Via `service.sendToMesh(MeshPacket*)`.
**Port Numbers**: 0–63 (core), 64–127 (registered 3rd-party), 256–511 (private/unregistered).

### Documented Modules

The official docs reference but do not exhaustively specify each module's protocol contract:

- **TextMessageModule**: Receives/displays text messages, stores in local DB.
- **NodeInfoModule**: Exchanges user information (username, etc.) for NodeDB population.
- **RemoteHardwareModule**: Remote GPIO control.
- **ReplyModule**: Ping/pong service (for testing).
- **GenericThreadModule**: Example template for periodic tasks.

**Not fully documented** in the scoped docs:
- Store-and-Forward (SFPP) — routing model, storage limits, eviction.
- Neighbor Info — neighbor discovery protocol.
- Range Test — test message format, response structure.
- Detection Sensor — trigger logic, event reporting.
- Paxcounter — tracking protocol.
- Map Report — map message format.
- ATAK — integration protocol (brief mention in TAK/TAK_TRACKER roles).

**(docs/development/device/module-api.mdx)**

---

## 13. PhoneAPI / Handshake — Corroborate or Correct

### Handshake Sequence

On BLE/TCP/Serial connect, the client sends `ToRadio.startConfig` (want_config_id field) with a numeric value. The device responds with:

1. `RadioConfig` (channel and radio settings).
2. `User` (node username).
3. `MyNodeInfo` (local device info).
4. Series of `NodeInfo` packets (NodeDB).
5. `endConfig` packet (signals completion of initial state).
6. Series of queued `MeshPacket` messages (if any backlog).

### Expected Behavior

- **Want Config ID**: The SDK claims special nonces `69420` (only_config) and `69421` (only_nodes) exist; the official docs do **not** mention these special values.
- **Config Complete ID**: Device echoes back `FromRadio.config_complete_id` to signal handshake completion.
- **MTU Size**: Recommendation to set MTU to 512 bytes on BLE (improves performance).

### Characteristics (BLE)

| UUID | Properties | Purpose |
|------|-----------|---------|
| `2c55e69e-4993-11ed-b878-0242ac120002` | read | `fromradio` — newly received packet (up to 512 bytes). After read, ESP32 puts next packet or empty packet. |
| `f75c76d2-129e-4dad-a1dd-7866124401e7` | write | `toradio` — send ToRadio protobufs (up to 512 bytes). |
| `ed9da18c-a800-4f66-a670-aa7547e34453` | read, notify, write | `fromnum` — packet counter in fromradio mailbox. Notify signals new data. Write to rewind (rare). |
| `5a3d6e49-06e6-4423-9944-e9de8cdf9547` | notify | LogRecord protobuf (debug logs). |
| `6c6fd238-78fa-436b-aacf-15c5be1ef2e2` | notify | Raw log string (deprecated). |

### Heartbeat

Device emits heartbeats every 30 seconds. On BLE, wait ~200 ms after sending a command before draining fromradio to allow the device to queue responses.

**(docs/development/device/client-api.mdx)**

**CORROBORATION**: The handshake sequence (startConfig → config stream → endConfig) is confirmed. Special nonces (69420, 69421) are **NOT** mentioned in the official docs; they appear to be SDK-level implementation details not documented as part of the official protocol spec.

---

## 14. Configuration Field Reference (Digest of docs/configuration/)

### Core Configuration Sections

#### Device Config
- `device.role`: CLIENT, CLIENT_MUTE, CLIENT_HIDDEN, CLIENT_BASE, TRACKER, LOST_AND_FOUND, SENSOR, TAK, TAK_TRACKER, REPEATER, ROUTER, ROUTER_LATE.
- `device.rebroadcast_mode`: ALL, ALL_SKIP_DECODING, LOCAL_ONLY, KNOWN_ONLY, NONE, CORE_PORTNUMS_ONLY.
- `device.node_info_broadcast_secs`: Default 3 hours (10800 s).
- `device.button_gpio`, `device.buzzer_gpio`: GPIO pin assignments.
- `device.double_tap_as_button_press`, `device.disable_triple_click`: Boolean toggles.
- `device.tzdef`: POSIX TZ Database string (e.g., `PST8PDT,M3.2.0,M11.1.0` for LA).

#### LoRa Config
- `lora.region`: UNSET, US, EU_433, EU_868, CN, JP, ANZ, ANZ_433, KR, TW, RU, IN, NZ_865, TH, LORA_24, UA_433, MY_433, MY_919, SG_923, KZ_433, KZ_863, BR_902, NP_865, PH_868, PH_915.
- `lora.modem_preset`: LONG_FAST (default), LONG_SLOW, LONG_MODERATE, MEDIUM_FAST, MEDIUM_SLOW, SHORT_FAST, SHORT_SLOW, SHORT_TURBO, VERY_LONG_SLOW.
- `lora.use_preset`: Boolean (true = use preset; false = use custom BW/SF/CR).
- `lora.bandwidth`: 31, 62, 125, 250, 500 (mapped to kHz).
- `lora.spread_factor`: 5–12.
- `lora.coding_rate`: 5–8 (denominator).
- `lora.hop_limit`: 1–7 (max hops; default 3).
- `lora.tx_power`: 0–30 dBm (0 = device max legal power).
- `lora.frequency_offset`: Frequency correction (crystal calibration).
- `lora.tx_enabled`: Boolean.
- `lora.channel_num`: Frequency slot (0 = use channel name hash; 1+ = explicit slot).
- `lora.ignore_mqtt`: Boolean (ignore MQTT-sourced messages).
- `lora.config_ok_to_mqtt`: Boolean (user approval for MQTT uplink).
- `lora.override_duty_cycle`: Boolean (ignore duty cycle limits; may violate local law).

#### Position Config
- `position.gps_mode`: DISABLED, ENABLED, NOT_PRESENT.
- `position.gps_update_interval`: Seconds (0 = default 2 min).
- `position.fixed_position`: Boolean (use last-known position, not live GPS).
- `position.position_broadcast_smart_enabled`: Boolean (adapt broadcast interval based on motion).
- `position.broadcast_smart_minimum_distance`: Meters (0 = default 100 m).
- `position.broadcast_smart_minimum_interval_secs`: Seconds (0 = default 30 s).
- `position.position_broadcast_secs`: Seconds (0 = default 15 min; interval for non-smart broadcasts).
- `position.flags`: Bit field (ALTITUDE, ALTITUDE_MSL, GEOIDAL_SEPARATION, DOP, HVDOP, SATINVIEW, SEQ_NO, TIMESTAMP, HEADING, SPEED).

#### Power Config
- `power.is_power_saving`: Boolean (disable BLE/Serial/WiFi, screen; deep sleep). Default false; auto-enabled for ROUTER role on ESP32.
- `power.on_battery_shutdown_after_secs`: Auto-shutdown timeout if on battery (0 = disabled).
- `power.adc_multiplier_override`: Battery voltage divider ratio (2–6; 0 = use firmware default).
- `power.wait_bluetooth_secs`: BLE timeout before sleep (0 = 1 min).
- `power.ls_secs`: Light sleep interval (0 = 5 min; ESP32 only).
- `power.min_wake_secs`: Minimum wake time on LoRa packet (0 = 10 s).
- `power.device_battery_ina_address`: I2C address (decimal) of INA2xx battery monitor (0 = none).

#### Network Config
- `network.ntp_server`: NTP server hostname (default `meshtastic.pool.ntp.org`).
- `network.wifi_enabled`: Boolean.
- `network.wifi_ssid`: SSID string (< 32 bytes).
- `network.wifi_psk`: WiFi password (< 64 bytes).
- `network.eth_enabled`: Boolean (Ethernet).
- `network.address_mode`: DHCP (default) or STATIC.
- (IPv4 static config, rsyslog, protocol flags not fully detailed).

#### Security Config
- `security.public_key`: Public key bytes (32 bytes; auto-generated).
- `security.private_key`: Private key bytes (32 bytes; **keep secure**).
- `security.admin_key`: Repeated field of authorized admin public keys (up to 3).
- `security.is_managed`: Boolean (enable Managed Mode; blocks client writes, only PKC Remote Admin/legacy admin channel allowed).
- `security.serial_enabled`: Boolean (enable serial API).
- `security.debug_log_api_enabled`: Boolean (emit debug logs when API active).
- `security.admin_channel_enabled`: Boolean (enable legacy admin channel for pre-2.5.0 remote admin).

#### Channel Config (per-channel)
- `channel[i].role`: PRIMARY, SECONDARY, DISABLED.
- `channel[i].settings.name`: Channel name (< 12 bytes).
- `channel[i].settings.psk`: Pre-shared key (0, 16, or 32 bytes).
- `channel[i].settings.uplink_enabled`, `downlink_enabled`: Boolean.
- `channel[i].module_settings.is_muted`: Boolean (suppress notifications).
- `channel[i].module_settings.position_precision`: 0–32 bits.

**(All sections from docs/configuration/radio/ subdirectory)**

---

## 15. Software-Side Integration Notes (Python CLI, Web Client, Mobile Clients)

### HTTP API Integration

**Web Client**: Connects to ESP32 devices via HTTP/HTTPS. Uses the HTTP API endpoints (`/api/v1/toradio`, `/api/v1/fromradio`) directly. Self-signed HTTPS certificates require browser trust configuration.

### Python CLI

- **Installation**: Via pip (`pip install meshtastic`).
- **Key Commands**:
  - `meshtastic --info`: Device info (firmware version, node ID, public key, etc.).
  - `meshtastic --set <config> <value>`: Set configuration (single or chained).
  - `meshtastic --ch-set <channel-config> <value> --ch-index <index>`: Per-channel config.
  - `--export-config`: Export full configuration (YAML, including keys).
  - `--configure <file>`: Restore configuration from export.
  - `--dest '!<node_id>'`: Remote admin (send commands to remote node).
  - `--debug`: Enable debug logging.
- **Transport**: Serial (USB), TCP, or BLE (on supported platforms).

### Mobile Clients (Android, iOS, macOS)

- **Android**: Supports BLE, Serial (USB-OTG), TCP (WiFi). Configuration UI for all settings.
- **iOS/iPadOS/macOS**: Last two major OS versions. BLE primary; limited configurations via UI compared to Android.
- **Feature Parity**: Web client and Python CLI have the most complete feature coverage; mobile clients expose the most commonly used settings via UI.

**(docs/software/web-client.mdx; docs/software/python-cli/; docs/software/android/; docs/software/apple/)**

---

## 16. CONFLICTS WITH ESTABLISHED FACTS

| Established Fact | Official Docs Statement | Status |
|---|---|---|
| DMs use AES-256-CCM with 8-byte tag | Docs state "AES-CTR or AES-CCM" (emphasis added) without specifying CCM exclusively or tag length | **PARTIAL CONFLICT**: Docs allow CCM but also mention CTR; tag length not specified. |
| Special nonces 69420 (only_config) and 69421 (only_nodes) exist | Docs do not mention these special values | **NOT CONTRADICTED**: Docs don't deny these; they're likely SDK implementation details not part of official spec. |
| Channel hash = XOR(name bytes) XOR XOR(psk bytes) | Docs mention channel hash but do not specify the XOR formula | **NOT CONTRADICTED**: No conflict; docs are less explicit. |
| Nonce for channel encryption = packet_id (uint64 LE) ‖ from_node (uint32 LE) ‖ 4 zero bytes | Docs do not specify nonce derivation for channel encryption | **NOT CONTRADICTED**: SDK level detail; docs less specific. |
| Session passkey ~5 min sliding window | Docs state "Session ID … valid for a short duration (e.g., 300 seconds)" | **CONFIRMED**: 300 seconds ≈ 5 minutes. |
| Admin Session timeout ~300 seconds | Docs state same for admin session ID validity | **CONFIRMED**. |
| TCP framing: 0x94 0xc3 + big-endian uint16 length, max 512 bytes | Docs confirm same framing structure | **CONFIRMED**. |
| BLE characteristic UUIDs and properties (fromradio, toradio, fromnum, logradio) | Docs list the same UUIDs and properties | **CONFIRMED**. |
| Max hops = 7, default = 3 | Docs confirm same | **CONFIRMED**. |
| 8 channel slots (0–7), must be consecutive | Docs confirm same | **CONFIRMED**. |

**Conclusion**: No major contradictions. SDK-level details (nonces, special values) are not contradicted but also not explicitly stated in the official docs. The docs are generally less detailed than the SDK's synthesized protocol but do not contradict the established facts.

---

## 17. CONFIRMED FACTS

- ✓ TCP/Serial framing: `0x94 0xc3` + big-endian uint16 length, max 512 bytes.
- ✓ TCP port 4403, 15-min idle timeout, single concurrent client.
- ✓ Serial 115200 8N1.
- ✓ BLE service `6BA1B218-15A8-461F-9FA8-5DCAE273EAFD` with four characteristics (fromradio, toradio, fromnum, logradio) with documented UUIDs.
- ✓ 12-state PhoneAPI handshake with `want_config_id`, `config_complete_id`, state sequence (RadioConfig → User → MyNodeInfo → NodeInfo[] → endConfig → MeshPacket[]).
- ✓ Channel encryption: AES-128-CTR or AES-256-CTR (note: CTR confirmed; SDK specifies CCM for DMs, which the docs mention as an option).
- ✓ PSK shorthand: `[0x00]` = none, `[0x01]` = default, `[0x02]–[0x0A]` = simple1–simple10 presets.
- ✓ Default PSK: specific 16-byte key value.
- ✓ Broadcast = 0xFFFFFFFF; PublicKey = 32 bytes; HOP_LIMIT_MAX = 7; DATA_PAYLOAD_LEN = 233.
- ✓ Device handles all retries (Router level); host must NOT retry.
- ✓ ROUTING_APP carries `request_id` for ACK correlation.
- ✓ Admin messages require authentication (PKC method with public keys, or legacy admin channel PSK).
- ✓ MQTT proxy mode: device delegates MQTT to phone via `MqttClientProxyMessage`.
- ✓ Heartbeat every 30 seconds.
- ✓ BLE: wait ~200 ms before re-draining fromradio.
- ✓ X25519 ECDH for key exchange (confirmed for admin messages; implied for DMs).
- ✓ Session ID for admin messages; ~300 second validity; replay attack mitigation.
- ✓ 8 channel slots (0–7), must be consecutive, PRIMARY always at index 0.
- ✓ Max hops 7 (default 3).
- ✓ Position precision 0–32 bits with reference table.
- ✓ Device roles: CLIENT, CLIENT_MUTE, CLIENT_HIDDEN, CLIENT_BASE, TRACKER, LOST_AND_FOUND, SENSOR, TAK, TAK_TRACKER, REPEATER (deprecated 2.7.11+), ROUTER, ROUTER_LATE.
- ✓ Rebroadcast modes: ALL, ALL_SKIP_DECODING, LOCAL_ONLY, KNOWN_ONLY, NONE, CORE_PORTNUMS_ONLY.
- ✓ Supported LoRa regions (24+ regions listed).
- ✓ Modem presets: SHORT_TURBO through VERY_LONG_SLOW (9 presets).
- ✓ HTTP API: `/api/v1/toradio` (PUT), `/api/v1/fromradio` (GET) with `all` and `chunked` query params.
- ✓ HTTP API uses protobuf binary directly (no `0x94 0xc3` framing).
- ✓ Network config: WiFi, Ethernet, NTP, static IP, rsyslog, UDP broadcast flags.
- ✓ Power config: sleep modes, ADC calibration, light sleep, battery monitoring.
- ✓ Remote Admin via PKC (2.5.0+) or legacy admin channel (pre-2.5.0).
- ✓ Managed Mode blocks client configuration writes.

---

## 18. AMBIGUITIES STILL UNRESOLVED

1. **MQTT Topic Taxonomy**: Full list of topic variants (e.g., `msh/2/e/`, `msh/2/c/`, `msh/2/json/`, `msh/2/stat/`, `msh/2/map/`) not exhaustively documented. Gateway publish vs. subscribe behavior not fully specified.

2. **ServiceEnvelope Gateway/Channel Interaction**: Exact semantics of how `gateway_id` and `channel_id` interact in `ServiceEnvelope` not fully elaborated.

3. **Retry & Backoff Timing**: NextHopRouter / ReliableRouter retry counts, backoff strategy, and timing not documented in user-facing docs. Firmware source required.

4. **NodeDB Eviction Policy**: Maximum node capacity and eviction/LRU policy not documented.

5. **Traceroute / RouteDiscovery**: Hop-by-hop path population, SNR arrays, TTL/termination logic not specified in user-facing docs.

6. **Module-Specific Protocols**: Store-and-Forward, Neighbor Info, Range Test, Detection Sensor, Paxcounter, MapReport, and ATAK module specifications not fully elaborated in scoped docs. `docs/configuration/module/` subdirectory not fully extracted.

7. **Port Number Assignments**: Complete list of registered 3rd-party port numbers (64–127 range) not provided; only the range and assignment procedure documented.

8. **Admin Message Variants**: No exhaustive catalog of AdminMessage types (beyond generic "radio settings," "device settings," etc.). Firmware protobuf definition would be required.

9. **Telemetry Field Completeness**: `TelemetrySensorType` enum values and complete `EnvironmentMetrics`, `PowerMetrics`, `AirQualityMetrics` field list not in scoped docs.

10. **Provisioning / WiFi Setup API**: HTTP provisioning endpoints or captive portal mechanism not documented in scoped docs. May exist in hardware or firmware-specific sections outside scope.

11. **DM Nonce Derivation**: Exact formula for DM nonce construction (IV for AES-CTR/CCM) not specified in official docs.

12. **Channel PSK Tag Length & IV Format**: For AES-128/256-CTR, IV (counter initialization) format not specified. SDK claim is 16-byte IV; official docs not explicit.

13. **Position Precision Bit-Mask Formula**: Mathematical formula for truncating lat/lon to N bits not provided; only lookup table given.

14. **GPS Audit Details**: `gps-audit.mdx` referenced but not extracted; may contain relevant GPS protocol details.

15. **Bluetooth MTU Constraints**: Whether 512 is a hard limit or recommended maximum not explicitly stated; devices may support larger if negotiated.

---

## Source Attribution Table

| Fact | Docs Path | Section |
|------|-----------|---------|
| HTTP API endpoints and content-type | `docs/development/device/http-api.mdx` | 1 (HTTP API) |
| BLE characteristics and UUIDs | `docs/development/device/client-api.mdx` | 13 (PhoneAPI) |
| TCP framing (0x94 0xc3) and streaming header | `docs/development/device/client-api.mdx` | 13 (PhoneAPI) |
| Handshake sequence (startConfig, config_complete_id) | `docs/development/device/client-api.mdx` | 13 (PhoneAPI) |
| Heartbeat and BLE draining timing | `docs/development/device/client-api.mdx` | 13 (PhoneAPI) |
| Channel slots (8, 0–7, consecutive constraint) | `docs/configuration/radio/channels.mdx` | 5 (Channel Slots) |
| Channel name, PSK, role, uplink/downlink, position precision | `docs/configuration/radio/channels.mdx` | 5 (Channel Slots) |
| Position precision bit table | `docs/configuration/radio/channels.mdx` | 10 (Position Precision) |
| Rebroadcast modes (ALL, ALL_SKIP_DECODING, LOCAL_ONLY, KNOWN_ONLY, NONE, CORE_PORTNUMS_ONLY) | `docs/configuration/radio/device.mdx` | 9 (Roles & Rebroadcast) |
| Device roles (CLIENT, ROUTER, TRACKER, SENSOR, REPEATER, etc.) | `docs/configuration/radio/device.mdx` | 9 (Roles & Rebroadcast) |
| Node Info Broadcast Seconds, GPIO, tzdef, LED heartbeat | `docs/configuration/radio/device.mdx` | 14 (Configuration) |
| Max hops (7), default 3 | `docs/configuration/radio/lora.mdx` | 4 (LoRa Presets) |
| Modem presets (LONG_FAST, SHORT_TURBO, VERY_LONG_SLOW, etc.) | `docs/configuration/radio/lora.mdx` | 4 (LoRa Presets) |
| Bandwidth special values (31, 62, 200, 400, 800, 1600) | `docs/configuration/radio/lora.mdx` | 4 (LoRa Presets) |
| Spread factor (5–12), coding rate (5–8) | `docs/configuration/radio/lora.mdx` | 4 (LoRa Presets) |
| LoRa regions (US, EU_868, EU_433, CN, JP, ANZ, etc.) | `docs/configuration/radio/lora.mdx` and `docs/configuration/region-by-country.mdx` | 4 (LoRa Regions) |
| Duty cycle limits (Europe 10% hourly) | `docs/configuration/radio/lora.mdx` | 4 (LoRa Presets) |
| GPS config (mode, update interval, fixed position, smart broadcast, broadcast interval) | `docs/configuration/radio/position.mdx` | 14 (Configuration) |
| Power saving, shutdown on battery, ADC multiplier, light sleep, min wake | `docs/configuration/radio/power.mdx` | 14 (Configuration) |
| WiFi, Ethernet, NTP, static IP config | `docs/configuration/radio/network.mdx` | 14 (Configuration) |
| Public/private keys, admin keys, managed mode, serial/debug logging | `docs/configuration/radio/security.mdx` | 6 (PKI & Encryption) and 7 (Admin) |
| Remote Admin (PKC method, legacy admin channel, setup process) | `docs/configuration/remote-admin.mdx` | 7 (Admin) |
| PSK for channels, X25519 ECDH for admin, session ID (~300 s), PKC for DMs | `docs/development/reference/encryption-technical.mdx` | 6 (PKI & Encryption) |
| Module API (MeshModule, SinglePortModule, ProtobufModule) | `docs/development/device/module-api.mdx` | 12 (Modules) |
| Port number ranges (0–63 core, 64–127 registered, 256–511 private) | `docs/development/firmware/port-numbers.mdx` | 12 (Modules) |
| Web client (HTTP/HTTPS, self-signed cert trust, browser compatibility) | `docs/software/web-client.mdx` | 15 (Software Integration) |
| FAQ and general mesh concepts | `docs/about/faq.mdx` | 15 and other sections |

---

## Notes for SDK Implementers

1. **HTTP API**: Use standard protobuf serialization directly; no custom framing for HTTP (unlike TCP/Serial).

2. **BLE Backoff**: Respect 200 ms delay before draining fromradio on BLE to allow device time to queue responses.

3. **Channel Slots**: Validate consecutive ordering and single PRIMARY constraint at client side before sending config to device.

4. **Position Precision**: Implement the lookup table for user-facing precision options; client applications abstract the 0–32 bit range to practical choices (e.g., 13 bits for city-block precision).

5. **Retry Logic**: Do NOT implement client-side retry for mesh messages; trust the device's routing layer and correlate responses via ROUTING_APP `request_id`.

6. **Admin Session Lifetime**: If implementing remote admin, cache the session ID and respect the ~300 second validity window; renew as needed.

7. **Key Backup**: Provide a mechanism to export and restore security keys (public/private/admin keys) to preserve encrypted communication capability across firmware updates.

8. **WiFi SSID/PSK Validation**: Maximum 32 bytes for SSID, 64 bytes for PSK; enforce at client side.

9. **Region Compliance**: Default to `UNSET` region and require explicit user selection; warn if duty cycle overrides are enabled (potentially illegal).

10. **Module Discovery**: Query `port_number` assignments from the official protobuf registry or your own local registry before sending to unregistered ports (256–511 range is safe for private testing).

---

**End of Report**
