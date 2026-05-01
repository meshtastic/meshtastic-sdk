# **Meshtastic PhoneAPI Wire-Level Protocol Reference**

## Overview

This is a comprehensive reference for the Meshtastic PhoneAPI protocol as defined in the authoritative protobuf schemas at **https://github.com/meshtastic/protobufs** (master branch).

**Source Citation:** From README.md:
> "The [Protobuf](https://developers.google.com/protocol-buffers) message definitions for the Meshtastic project (used by apps and the device firmware). **[Documentation/API Reference](https://buf.build/meshtastic/protobufs)**"

API stability is maintained through careful field numbering and oneof versioning. Schemas are published to multiple language SDKs (Go, Python, Java, Swift, Kotlin).

---

## §1: Root Envelopes (ToRadio / FromRadio)

### **ToRadio (Phone → Device)**

**Source:** `mesh.proto`

```protobuf
message ToRadio {
  oneof payload_variant {
    MeshPacket packet = 1;
    uint32 want_config_id = 3;
    bool disconnect = 4;
    XModem xmodemPacket = 5;
    MqttClientProxyMessage mqttClientProxyMessage = 6;
    Heartbeat heartbeat = 7;
  }
}
```

**Key semantics:**
- `want_config_id`: Request full device config dump; radio echoes this ID in `config_complete_id` response to distinguish stale from fresh configs.
- `disconnect`: Optional graceful close signal (particularly useful for serial transports without hardware flow control).

### **FromRadio (Device → Phone)**

**Source:** `mesh.proto`

```protobuf
message FromRadio {
  uint32 id = 1;
  oneof payload_variant {
    MeshPacket packet = 2;
    MyNodeInfo my_info = 3;
    NodeInfo node_info = 4;
    Config config = 5;
    LogRecord log_record = 6;
    uint32 config_complete_id = 7;
    bool rebooted = 8;
    ModuleConfig moduleConfig = 9;
    Channel channel = 10;
    QueueStatus queueStatus = 11;
    XModem xmodemPacket = 12;
    DeviceMetadata metadata = 13;
    MqttClientProxyMessage mqttClientProxyMessage = 14;
    FileInfo fileInfo = 15;
    ClientNotification clientNotification = 16;
    DeviceUIConfig deviceuiConfig = 17;
  }
}
```

---

## §2: MeshPacket Structure + Enums

**Source:** `mesh.proto`

### **MeshPacket Message**

```protobuf
message MeshPacket {
  fixed32 from = 1;              // Sending node (32-bit ID)
  fixed32 to = 2;                // Destination (0xFFFFFFFF = broadcast)
  uint32 channel = 3;
  oneof payload_variant {
    Data decoded = 4;            // Decoded application payload
    bytes encrypted = 5;         // 128-bit AES encrypted
  }
  fixed32 id = 6;                // Unique per-sender packet ID
  float rx_rssi = 7;
  float rx_snr = 8;
  uint32 hop_limit = 9;          // Remaining hops (0–7; 3-bit field)
  bool want_ack = 10;            // Request ack
  Priority priority = 11;        // Internal queue priority (not sent over air)
  int32 rx_rssi = 12;
  Delayed delayed = 13;          // [DEPRECATED]
  bool via_mqtt = 14;
  uint32 hop_start = 15;         // Initial hop_limit (allows hop distance calc)
  bytes public_key = 16;         // 32-byte Curve25519 key (if PKI-encrypted)
  bool pki_encrypted = 17;       // True if PKI vs. channel PSK
  uint32 next_hop = 18;          // [INTERNAL]
  uint32 relay_node = 19;        // [INTERNAL]
  uint32 tx_after = 20;          // [INTERNAL] Unix timestamp for transmission
  TransportMechanism transport_mechanism = 21;
}
```

### **Critical Constants**
- **HOP_LIMIT_MAX:** 7 (3-bit field)
- **PublicKey size:** 32 bytes (Curve25519)
- **Broadcast address:** 0xFFFFFFFF (4,294,967,295)

### **Priority Enum**

```protobuf
enum Priority {
  UNSET = 0;        // → defaults to DEFAULT
  MIN = 1;
  BACKGROUND = 10;  // Low-priority (periodic position)
  DEFAULT = 64;
  RELIABLE = 70;    // want_ack=true
  RESPONSE = 80;    // Response to request
  HIGH = 100;
  ALERT = 110;      // Critical alert
  ACK = 120;        // Acks/Naks (highest)
  MAX = 127;
}
```

**Note:** Priority is **internal only**; never sent over the air. Controls device tx queue ordering.

### **TransportMechanism Enum**

```protobuf
enum TransportMechanism {
  TRANSPORT_INTERNAL = 0;
  TRANSPORT_LORA = 1;
  TRANSPORT_LORA_ALT1 = 2;    // Secondary LoRa radio
  TRANSPORT_LORA_ALT2 = 3;
  TRANSPORT_LORA_ALT3 = 4;
  TRANSPORT_MQTT = 5;
  TRANSPORT_MULTICAST_UDP = 6;
  TRANSPORT_API = 7;
}
```

### **Constants Enum**

```protobuf
enum Constants {
  ZERO = 0;
  DATA_PAYLOAD_LEN = 233;     // Max Data.payload bytes (excludes 16-byte LoRa header)
}
```

---

## §3: PortNums (Full Table)

**Source:** `portnums.proto`

### **PortNum Ranges**
- **0–63:** Core Meshtastic (reserved)
- **64–127:** Registered 3rd-party (PR required for reservation)
- **256–511:** Private/experimental (no registration needed)
- **Others:** Reserved

### **Full Enumeration**

| Value | Name | Encoding | Purpose |
|-------|------|----------|---------|
| 0 | UNKNOWN_APP | Binary (undefined) | Legacy/unknown payloads |
| 1 | TEXT_MESSAGE_APP | UTF-8 plaintext | Text messaging |
| 2 | REMOTE_HARDWARE_APP | Protobuf (HardwareMessage) | GPIO read/write/watch |
| 3 | POSITION_APP | Protobuf (Position) | Location data |
| 4 | NODEINFO_APP | Protobuf (User) | Node info (name, key, role) |
| 5 | ROUTING_APP | Protobuf (Routing) | Mesh routing protocol |
| 6 | ADMIN_APP | Protobuf (AdminMessage) | Device settings, reboot, factory reset |
| 7 | TEXT_MESSAGE_COMPRESSED_APP | Unishox2 compressed UTF-8 | Text (auto-converted by firmware) |
| 8 | WAYPOINT_APP | Protobuf (Waypoint) | Waypoints |
| 9 | AUDIO_APP | Codec2 frames | Voice (header: 0xC0 0xDE 0xC2) |
| 10 | DETECTION_SENSOR_APP | UTF-8 plaintext | Detection sensor alerts (internal only) |
| 11 | ALERT_APP | UTF-8 plaintext | Critical alerts |
| 12 | KEY_VERIFICATION_APP | Protobuf | Key verification handshake |
| 13 | REMOTE_SHELL_APP | Binary | Primitive remote shell |
| 32 | REPLY_APP | ASCII plaintext | Echo/ping (firmware auto-replies) |
| 33 | IP_TUNNEL_APP | IP packet | Python bridge tunnel (firmware ignores) |
| 34 | PAXCOUNTER_APP | Protobuf (Paxcount) | WiFi/BLE device counting |
| 35 | STORE_FORWARD_PLUSPLUS_APP | Protobuf | S&F v2 (Git-style chain, Linux only) |
| 36 | NODE_STATUS_APP | Protobuf | Custom node status string |
| 64 | SERIAL_APP | Binary | Serial bridge (38400 8N1; max 240 bytes) |
| 65 | STORE_FORWARD_APP | Protobuf (StoreAndForward) | S&F v1 (work in progress) |
| 66 | RANGE_TEST_APP | ASCII plaintext | Range test (internal only; not to MQTT) |
| 67 | TELEMETRY_APP | Protobuf (Telemetry) | Sensor telemetry |
| 68 | ZPS_APP | int64 arrays | Zero-GPS positioning |
| 69 | SIMULATOR_APP | Protobuf | Simulator (Linux) |
| 70 | TRACEROUTE_APP | Protobuf (RouteDiscovery) | Route tracing |
| 71 | NEIGHBORINFO_APP | Protobuf (NeighborInfo) | Neighbor aggregation |
| 72 | ATAK_PLUGIN | Protobuf | ATAK plugin (official) |
| 73 | MAP_REPORT_APP | Protobuf (MapReport) | Unencrypted node info for MQTT map |
| 74 | POWERSTRESS_APP | Protobuf (PowerStressMessage) | Power consumption testing |
| 75 | LORAWAN_BRIDGE | LoRaWAN uplink | 10-byte RF metadata + PHY payload |
| 76 | RETICULUM_TUNNEL_APP | Fragmented RNS packets | Reticulum tunnel |
| 77 | CAYENNE_APP | CayenneLLP | Arbitrary telemetry (LoRaWAN-compatible) |
| 78 | ATAK_PLUGIN_V2 | TAKPacketV2 (zstd) | ATAK v2 (compressed) |
| 112 | GROUPALARM_APP | Protobuf | GroupAlarm integration |
| 256 | PRIVATE_APP | [varies] | Default private app port |
| 257 | ATAK_FORWARDER | libcotshrink | ATAK Forwarder |
| 511 | MAX | – | Ceiling |

---

## §4: Per-PortNum Payload Schemas

### **Data Wrapper (Carries All PortNum Payloads)**

**Source:** `mesh.proto`

```protobuf
message Data {
  PortNum portnum = 1;           // Dispatcher field
  bytes payload = 2;             // Raw payload (interpretation per portnum)
  bool want_response = 3;        // Recipient echo back if possible (⚠️ risky on broadcast)
  fixed32 dest = 4;              // [INTERNAL] Multihop destination
  fixed32 source = 5;            // [INTERNAL] Multihop source
  fixed32 request_id = 6;        // [ROUTING] Failure responses reference this
  fixed32 reply_id = 7;          // [RESPONSE] This is a reply to message with this ID
  fixed32 emoji = 8;             // [EMOJI] Reaction code if true
  optional uint32 bitfield = 9;  // Flags (bit 0 = MQTT upload approved)
}
```

### **Position Payload**

**Source:** `mesh.proto`

```protobuf
message Position {
  optional sfixed32 latitude_i = 1;    // × 1e-7 = decimal degrees
  optional sfixed32 longitude_i = 2;   // × 1e-7 = decimal degrees
  optional int32 altitude = 3;         // meters above MSL
  fixed32 time = 4;                    // Unix timestamp (not usually sent over mesh)
  enum LocSource {
    LOC_UNSET = 0; LOC_MANUAL = 1; LOC_INTERNAL = 2; LOC_EXTERNAL = 3;
  }
  LocSource location_source = 5;
  enum AltSource {
    ALT_UNSET = 0; ALT_MANUAL = 1; ALT_INTERNAL = 2; ALT_EXTERNAL = 3; ALT_BAROMETRIC = 4;
  }
  AltSource altitude_source = 6;
  fixed32 timestamp = 7;               // Positional timestamp
  int32 timestamp_millis_adjust = 8;
  optional sint32 altitude_hae = 9;    // Height Above Ellipsoid
  optional sint32 altitude_geoidal_separation = 10;
  uint32 PDOP = 11;                    // Position Dilution of Precision (1/100)
  uint32 HDOP = 12; uint32 VDOP = 13;
  uint32 gps_accuracy = 14;            // Hardware constant (mm) × DOP
  optional uint32 ground_speed = 15;   // m/s
  optional uint32 ground_track = 16;   // Degrees (1/100)
  uint32 fix_quality = 17;             // NMEA GxGGA
  uint32 fix_type = 18;                // NMEA GxGSA (2D/3D)
  uint32 sats_in_view = 19;
  uint32 sensor_id = 20;               // Multi-sensor ID
  uint32 next_update = 21;             // Seconds until next update
  uint32 seq_number = 22;              // Sequence for tracking losses
  uint32 precision_bits = 23;          // Precision mask from origin
}
```

### **User Payload (NODEINFO_APP)**

**Source:** `mesh.proto`

```protobuf
message User {
  string id = 1;                    // Unique ID ("!AABBCCDD" or "+1234567890")
  string long_name = 2;            // Full name
  string short_name = 3;           // 2-character shortname (OLED-friendly)
  bytes macaddr = 4;               // [DEPRECATED 2.1+]
  HardwareModel hw_model = 5;      // Device type
  bool is_licensed = 6;            // Ham radio licensed
  Config.DeviceConfig.Role role = 7;  // Device role
  bytes public_key = 8;            // 32-byte Curve25519 (PKI encryption)
}
```

### **Routing Payload (ROUTING_APP)**

**Source:** `mesh.proto`

```protobuf
message Routing {
  enum Error {
    NONE = 0; NO_ROUTE = 1; GOT_NAK = 2; TIMEOUT = 3; NO_INTERFACE = 4;
    MAX_RETRANSMIT = 5; NO_CHANNEL = 6; TOO_LARGE = 7; NO_RESPONSE = 8;
    DUTY_CYCLE_LIMIT = 9; BAD_REQUEST = 32; NOT_AUTHORIZED = 33;
    PKI_FAILED = 34; PKI_UNKNOWN_PUBKEY = 35; ADMIN_BAD_SESSION_KEY = 36;
    ADMIN_PUBLIC_KEY_UNAUTHORIZED = 37; RATE_LIMIT_EXCEEDED = 38;
    PKI_SEND_FAIL_PUBLIC_KEY = 39;
  }
  oneof variant {
    RouteDiscovery route_request = 1;
    RouteDiscovery route_reply = 2;
    Error error_reason = 3;
  }
}
```

### **Telemetry Payload (TELEMETRY_APP)**

**Source:** `telemetry.proto`

```protobuf
message Telemetry {
  fixed32 time = 1;  // Unix timestamp or 0
  oneof variant {
    DeviceMetrics device_metrics = 2;
    EnvironmentMetrics environment_metrics = 3;
    AirQualityMetrics air_quality_metrics = 4;
    PowerMetrics power_metrics = 5;
    LocalStats local_stats = 6;
    HealthMetrics health_metrics = 7;
    HostMetrics host_metrics = 8;
    TrafficManagementStats traffic_management_stats = 9;
  }
}
```

**DeviceMetrics:** `battery_level` (0–100, >100=powered), `voltage`, `channel_utilization`, `air_util_tx`, `uptime_seconds`

**EnvironmentMetrics:** `temperature`, `relative_humidity`, `barometric_pressure`, `gas_resistance`, `wind_speed`, `rainfall_1h`, `soil_moisture`, `lux`, `uv_lux`, `distance`, `weight`, etc.

**PowerMetrics:** `ch1_voltage`, `ch1_current`, ... `ch8_voltage`, `ch8_current` (8 channels)

**LocalStats:** `uptime_seconds`, `channel_utilization`, `air_util_tx`, `num_packets_tx`, `num_packets_rx`, `num_packets_rx_bad`, `num_online_nodes`, `num_total_nodes`, `heap_total_bytes`, `heap_free_bytes`, `noise_floor`

---

## §5: Channels & Encryption Keys

**Source:** `channel.proto`

### **Channel Message**

```protobuf
message Channel {
  int32 index = 1;              // Channel index (0 to MAX_NUM_CHANNELS-1)
  ChannelSettings settings = 2; // Settings (null to disable)
  enum Role {
    DISABLED = 0;    // Not in use
    PRIMARY = 1;     // Sets radio frequency; only one can be PRIMARY
    SECONDARY = 2;   // Encryption/decryption only; freq ignored
  }
  Role role = 3;
}
```

### **ChannelSettings Message**

```protobuf
message ChannelSettings {
  uint32 channel_num = 1;   // [DEPRECATED] Use LoraConfig.channel_num
  bytes psk = 2;            // Pre-shared key: 0, 16, or 32 bytes
  string name = 3;          // Channel name (<12 bytes); "" = default "X"
  fixed32 id = 4;           // Global unique ID (random); display: "name.id" (base36)
  bool uplink_enabled = 5;  // Forward to MQTT
  bool downlink_enabled = 6; // Accept from MQTT
  ModuleSettings module_settings = 7;
}
```

### **PSK Shorthand (1-byte values expand to 16-byte keys)**

| Byte Value | Meaning |
|-----------|---------|
| 0 | No encryption |
| 1 | **Default key:** `{0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x07, 0x59, 0xf0, 0xbc, 0xff, 0xab, 0xcf, 0x4e, 0x69, 0x01}` |
| 2–10 | Default + (1–9 added to last byte) — labeled "simple1" to "simple10" |

**Channel Name Hash (for display letter A–Z):**

From inline comment: `0x41 + [xor all bytes of psk] modulo 26`

---

## §6: Configs & ModuleConfigs (Full Inventory)

**Sources:** `config.proto`, `module_config.proto`

### **Config Sub-Messages (Device Configs)**

**All phone-visible. Sent during config dump.**

```protobuf
message Config {
  oneof payload_variant {
    DeviceConfig device = 1;         // Role, call_sign, antenna_gain, rebroadcast_mode, is_managed_locally
    PositionConfig position = 2;     // position_broadcast_secs, pos_flags, gps_attempt_time
    PowerConfig power = 3;            // charge_current_ma, channel_drain, is_power_saving, low_power_min_volts
    NetworkConfig network = 4;       // (WiFi, BLE, network-level settings)
    DisplayConfig display = 5;       // Screen brightness, turn_off_on_tx
    LoRaConfig lora = 6;              // Frequency, SF, CR, BW, region, modem_preset, channel_num, tx_power, etc.
    BluetoothConfig bluetooth = 7;   // tx_power, enabled
    SecurityConfig security = 8;      // admin_channel_index, local_device_secret, location_secret
    DeviceUIConfig device_ui = 9;    // [2.1+] Persistent UI state
  }
}
```

### **ModuleConfig Sub-Messages (Module Configs)**

**All phone-visible. Sent during config dump.**

```protobuf
message ModuleConfig {
  oneof payload_variant {
    MQTTConfig mqtt = 1;                      // enabled, address, username, password, encryption_enabled, json_enabled, tls_enabled, root, proxy_to_client_enabled, map_reporting_enabled, map_report_settings
    SerialConfig serial = 2;                  // enabled, echo, rxd/txd, baud, timeout, mode (SIMPLE/PROTO/TEXTMSG/NMEA/CALTOPO/LOG/etc.), override_console_serial_port
    ExternalNotificationConfig ext_notif = 3; // enabled, output_ms, output/output_vibra/output_buzzer pins, alert_message
    StoreForwardConfig store_forward = 4;     // enabled, records (history size), history_return_max, history_return_window, heartbeat_enabled
    RangeTestConfig range_test = 5;           // enabled, save_csv, csv_filename
    TelemetryConfig telemetry = 6;            // enabled, devicemetrics_enabled/interval, environmentmetrics_enabled/interval, sensor_type, battery_icon_percentage
    CannedMessageConfig canned_message = 7;   // messages (pipe-separated string)
    AudioConfig audio = 8;                    // codec2_enabled, ptt_pin, bitrate (CODEC2_3200/2400/1600/etc.), I2S pins
    RemoteHardwareConfig remote_hw = 9;       // enabled, allow_undefined_pin_access, available_pins list
    NeighborInfoConfig neighbor_info = 10;    // enabled, update_interval, transmit_over_lora
    AmbientLightingConfig ambient_lighting = 11;  // enabled, blink_on_rx, led_state, etc.
    DetectionSensorConfig detection_sensor = 12;  // enabled, minimum_broadcast_secs, state_broadcast_secs, name, monitor_pin, detection_trigger_type (LOGIC_LOW/HIGH/RISING_EDGE/FALLING_EDGE/etc.), use_pullup
    PaxcounterConfig paxcounter = 13;         // enabled, paxcounter_update_interval, wifi/ble_threshold
    StatusMessageConfig status_message = 14;  // enabled, status_broadcast_secs, status_text
    TrafficManagementConfig traffic_mgmt = 15; // enabled, position_dedup_enabled, position_precision_bits, rate_limit_enabled, rate_limit_window_secs, rate_limit_max_packets, drop_unknown_enabled, etc.
    TAKConfig tak = 16;                       // enabled, tak_enabled, server, port, certificate_file, encryption_enabled, etc.
  }
}
```

---

## §7: Admin Protocol (Full AdminMessage Variant Table)

**Source:** `admin.proto`

### **AdminMessage Structure**

```protobuf
message AdminMessage {
  bytes session_passkey = 101;  // Key expires 300 seconds; echo in all set_* commands (prevents replay)

  enum ConfigType { DEVICE_CONFIG=0, POSITION_CONFIG=1, POWER_CONFIG=2, NETWORK_CONFIG=3, DISPLAY_CONFIG=4,
                    LORA_CONFIG=5, BLUETOOTH_CONFIG=6, SECURITY_CONFIG=7, SESSIONKEY_CONFIG=8, DEVICEUI_CONFIG=9 }

  enum ModuleConfigType { MQTT_CONFIG=0, SERIAL_CONFIG=1, EXTNOTIF_CONFIG=2, STOREFORWARD_CONFIG=3,
                          RANGETEST_CONFIG=4, TELEMETRY_CONFIG=5, CANNEDMSG_CONFIG=6, AUDIO_CONFIG=7,
                          REMOTEHARDWARE_CONFIG=8, NEIGHBORINFO_CONFIG=9, AMBIENTLIGHTING_CONFIG=10,
                          DETECTIONSENSOR_CONFIG=11, PAXCOUNTER_CONFIG=12, STATUSMESSAGE_CONFIG=13,
                          TRAFFICMANAGEMENT_CONFIG=14, TAK_CONFIG=15 }

  enum BackupLocation { FLASH=0; SD=1; }

  oneof payload_variant {
    // GET OPERATIONS
    uint32 get_channel_request = 1;
    Channel get_channel_response = 2;
    bool get_owner_request = 3;
    User get_owner_response = 4;
    ConfigType get_config_request = 5;
    Config get_config_response = 6;
    ModuleConfigType get_module_config_request = 7;
    ModuleConfig get_module_config_response = 8;
    bool get_canned_message_module_messages_request = 10;
    string get_canned_message_module_messages_response = 11;
    bool get_device_metadata_request = 12;
    DeviceMetadata get_device_metadata_response = 13;
    bool get_ringtone_request = 14;
    string get_ringtone_response = 15;
    bool get_device_connection_status_request = 16;
    DeviceConnectionStatus get_device_connection_status_response = 17;
    bool get_node_remote_hardware_pins_request = 19;
    NodeRemoteHardwarePinsResponse get_node_remote_hardware_pins_response = 20;
    bool get_ui_config_request = 44;
    DeviceUIConfig get_ui_config_response = 45;

    // SET OPERATIONS
    User set_owner = 32;
    Channel set_channel = 33;
    Config set_config = 34;
    ModuleConfig set_module_config = 35;
    string set_canned_message_module_messages = 36;
    string set_ringtone_message = 37;
    Position set_fixed_position = 41;
    bool remove_fixed_position = 42;
    DeviceUIConfig store_ui_config = 46;

    // TRANSACTION CONTROL
    bool begin_edit_settings = 64;    // Batch edits; delay save/reboot
    bool commit_edit_settings = 65;   // Commit; trigger save/reboot

    // CONTACT & KEY VERIFICATION
    SharedContact add_contact = 66;
    KeyVerificationAdmin key_verification = 67;

    // DEVICE CONTROL
    bool enter_dfu_mode_request = 21;
    OTAEvent ota_request = 102;
    int32 reboot_seconds = 97;          // Reboot in N sec (<0 to cancel)
    int32 shutdown_seconds = 98;        // Shutdown in N sec (<0 to cancel)
    int32 factory_reset_device = 94;    // Wipe all state + BLE bonds
    int32 factory_reset_config = 99;    // Config only (preserve BLE)
    bool nodedb_reset = 100;            // Reset node DB (preserve favorites)

    // FILE OPERATIONS
    string delete_file_request = 22;
    BackupLocation backup_preferences = 24;
    BackupLocation restore_preferences = 25;
    BackupLocation remove_backup_preferences = 26;

    // NODE MANAGEMENT
    uint32 remove_by_nodenum = 38;
    uint32 set_favorite_node = 39;
    uint32 remove_favorite_node = 40;
    uint32 set_ignored_node = 47;
    uint32 remove_ignored_node = 48;
    uint32 toggle_muted_node = 49;

    // MISCELLANEOUS
    HamParameters set_ham_mode = 18;
    uint32 set_scale = 23;              // NAU7802 scale chip
    InputEvent send_input_event = 27;
    fixed32 set_time_only = 43;         // Unix timestamp
    SensorConfig sensor_config = 103;   // SCD4X, SEN5X, SCD30, SHTXX setup
    bool exit_simulator = 96;           // [SIM ONLY]
  }
}
```

### **Key Admin Sub-Messages**

```protobuf
message OTAEvent {
  OTAMode reboot_ota_mode = 1;  // NO_REBOOT_OTA=0, OTA_BLE=1, OTA_WIFI=2
  bytes ota_hash = 2;            // 32-byte SHA256 hash for integrity
}

message KeyVerificationAdmin {
  enum MessageType {
    INITIATE_VERIFICATION = 0;
    PROVIDE_SECURITY_NUMBER = 1;
    DO_VERIFY = 2;
    DO_NOT_VERIFY = 3;
  }
  MessageType message_type = 1;
  uint32 remote_nodenum = 2;
  uint64 nonce = 3;              // Session tracking
  optional uint32 security_number = 4;  // 4-digit code
}

message SharedContact {
  uint32 node_num = 1;
  User user = 2;
  bool should_ignore = 3;
  bool manually_verified = 4;
}

message HamParameters {
  string call_sign = 1;
  int32 tx_power = 2;            // dBm
  float frequency = 3;           // Hz
  string short_name = 4;
}
```

---

## §8: MQTT Proxy (ServiceEnvelope / MqttClientProxyMessage)

**Sources:** `mqtt.proto`, `mesh.proto`

### **ServiceEnvelope**

**Source:** `mqtt.proto`

```protobuf
message ServiceEnvelope {
  MeshPacket packet = 1;        // Mesh packet (encrypted or not)
  string channel_id = 2;        // Global channel ID
  string gateway_id = 3;        // Sending gateway node ID
}
```

**Use:** Wraps mesh packets for MQTT publication with authentication metadata.

### **MqttClientProxyMessage**

**Source:** `mesh.proto`

```protobuf
message MqttClientProxyMessage {
  string topic = 1;            // MQTT topic path
  oneof payload_variant {
    bytes data = 2;            // Binary (e.g., encoded ServiceEnvelope)
    string text = 3;           // UTF-8 text
  }
  bool retained = 4;           // MQTT retain flag
}
```

**Bidirectional:**
- **ToRadio:** Phone reads MQTT; wraps in MqttClientProxyMessage; device forwards to mesh.
- **FromRadio:** Device publishes to MQTT; sends MqttClientProxyMessage; phone publishes on its behalf.

### **MapReport (Unencrypted Node Info)**

**Source:** `mqtt.proto`

```protobuf
message MapReport {
  string long_name = 1;
  string short_name = 2;
  Config.DeviceConfig.Role role = 3;
  HardwareModel hw_model = 4;
  string firmware_version = 5;
  Config.LoRaConfig.RegionCode region = 6;
  Config.LoRaConfig.ModemPreset modem_preset = 7;
  bool has_default_channel = 8;
  sfixed32 latitude_i = 9;      // Multiply by 1e-7 (when opted-in)
  sfixed32 longitude_i = 10;
  int32 altitude = 11;
  uint32 position_precision = 12;
  uint32 num_online_local_nodes = 13;
  bool has_opted_report_location = 14;
}
```

---

## §9: Firmware Update (XModem)

**Source:** `xmodem.proto`

```protobuf
message XModem {
  enum Control {
    NUL = 0;      // No-op
    SOH = 1;      // 128-byte block
    STX = 2;      // 1024-byte block
    EOT = 4;      // End of transmission
    ACK = 6;      // Ack
    NAK = 21;     // Nack (retry)
    CAN = 24;     // Cancel
    CTRLZ = 26;   // End of file marker
  }
  Control control = 1;
  uint32 seq = 2;
  uint32 crc16 = 3;
  bytes buffer = 4;
}
```

**Block payload sizes:** 128 or 1024 bytes depending on SOH vs STX.

---

## §10: Constants & Magic Numbers

| Constant | Value | Source | Notes |
|----------|-------|--------|-------|
| DATA_PAYLOAD_LEN | 233 bytes | mesh.proto Constants | Max Data.payload (16-byte LoRa header excluded) |
| HOP_LIMIT_MAX | 7 | [3-bit field] | Implicit max |
| PublicKey size | 32 bytes | [Curve25519] | For PKI encryption |
| Session passkey expiry | 300 seconds | admin.proto | Admin session timeout |
| Default PSK | `{0xd4,0xf1,0xbb,0x3a,0x20,0x29,0x07,0x59,0xf0,0xbc,0xff,0xab,0xcf,0x4e,0x69,0x01}` | channel.proto | Shorthand value = 1 |
| Broadcast address | 0xFFFFFFFF (4,294,967,295) | [MeshPacket.to] | Not 0 or 0xFF! |
| Battery >100 | Powered (not battery) | telemetry.proto | DeviceMetrics.battery_level |

---

## §11: Notable Findings & Implementer Pitfalls

### **1. Broadcast is 0xFFFFFFFF, Not 0x00**
Critical: `MeshPacket.to = 0xFFFFFFFF` means "broadcast to entire channel." Common mistake: assuming 0 or 0xFF.

### **2. PublicKey is Always 32 Bytes**
PKI uses Curve25519 keys. No variable sizing. Present in MeshPacket (field 16) and User (field 8).

### **3. Default PSK Byte Value 1 Expands**
Byte `1` → 16-byte key. Byte `0` → no encryption. Bytes `2–10` → default +1 to +9 on last byte ("simple1"–"simple10").

### **4. Channel Index vs. Channel ID**
- **index** (0–15): Local per-device.
- **id** (random 32-bit): Global; display name is "name.id" (base36).

### **5. Admin Session Passkey Expires in 300 Seconds**
Must echo in all `set_*` commands. New key issued per `get_*` response. Prevents replay attacks.

### **6. hop_limit vs. hop_start are Not Interchangeable**
- `hop_limit`: Remaining hops (decremented at each relay).
- `hop_start`: Initial hop count.
- Hop distance = hop_start − hop_limit.

### **7. Position Coordinates are Scaled by 1e-7**
`latitude_i = 37123456` → 3.7123456° N. Always divide by 10,000,000.

### **8. Data.payload Interpretation Depends on portnum**
- **TEXT_MESSAGE_APP (1):** UTF-8 string.
- **POSITION_APP (3):** Protobuf Position.
- **ADMIN_APP (6):** Protobuf AdminMessage.
Decoder must dispatch on portnum.

### **9. FromRadio.id vs. config_complete_id**
- `id` (field 1): FIFO packet ID (all messages).
- `config_complete_id` (in payload): Echoes `want_config_id` from ToRadio; marks end of config dump.

### **10. REPLY_APP (32) Auto-Echoes Any Packet**
Useful for testing connectivity. Firmware replies automatically.

### **11. Factory Reset Variants**
- `factory_reset_device` (94): Wipes all state; clears BLE bonds.
- `factory_reset_config` (99): Config only; preserves BLE bonds.
- `nodedb_reset` (100): Clears node DB; preserves favorites.

### **12. Channel PRIMARY vs. SECONDARY**
- **PRIMARY (1):** Sets radio frequency; only one per device.
- **SECONDARY (2):** Frequency ignored; used for encryption/decryption only.

### **13. Telemetry Oneof Variants are Mutually Exclusive**
Each Telemetry message holds exactly ONE variant (DeviceMetrics, EnvironmentMetrics, etc.). Multiple types sent as separate messages.

### **14. SerialConfig Modes Support Diverse Protocols**
DEFAULT, SIMPLE, PROTO, TEXTMSG, NMEA, CALTOPO, WS85, VE_DIRECT, MS_CONFIG, LOG, LOGTEXT.

### **15. Canned Messages are Pipe-Separated**
Example: `"Hello|I'm mobile|Position?"`. Not newline-separated. Phone presents as buttons.

### **16. MqttClientProxyMessage is NOT a Mesh Packet**
It's a side-channel for MQTT proxy functionality. Device offloads MQTT publishing to connected phone.

### **17. Position Precision Bits (precision_bits field)**
Indicates how many bits of precision the origin has. Receivers can round coordinates accordingly (0–32 bits).

### **18. Priority is Internal; Never Sent Over Air**
MeshPacket.priority only controls device tx queue ordering. Not serialized in the LoRa packet.

### **19. Transport Mechanism Indicates Arrival Path**
TRANSPORT_LORA, TRANSPORT_MQTT, TRANSPORT_MULTICAST_UDP, TRANSPORT_API, etc. Useful for debugging multi-hop vs. MQTT-routed packets.

### **20. begin_edit_settings / commit_edit_settings Batch Changes**
Wrap multiple `set_config` / `set_module_config` calls between begin and commit to delay reboot until all changes are applied.

---

## **Bibliography & Sources**

All information extracted from:
- **`meshtastic/protobufs`** (GitHub): https://github.com/meshtastic/protobufs/tree/master/meshtastic/
- **README.md**: https://github.com/meshtastic/protobufs/blob/master/README.md
- **buf.build docs**: https://buf.build/meshtastic/protobufs

Key files referenced:
- `mesh.proto` (2,680 lines): ToRadio, FromRadio, MeshPacket, Data, Position, User, Routing, NodeInfo, MyNodeInfo
- `admin.proto`: AdminMessage (full command set)
- `channel.proto`: Channel, ChannelSettings, PSK mechanics
- `portnums.proto`: PortNum enumeration (0–511)
- `config.proto`: All device configs
- `module_config.proto`: All module configs
- `telemetry.proto`: Telemetry message variants
- `mqtt.proto`: ServiceEnvelope, MapReport, MqttClientProxyMessage
- `xmodem.proto`: XModem firmware transfer protocol

---

**Document prepared for:** Clean-room Kotlin Multiplatform SDK development

**Last updated:** Against master branch (https://github.com/meshtastic/protobufs/tree/master/)
