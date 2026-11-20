# Module transport-ble

Bluetooth LE transport for the Meshtastic KMP SDK. It implements `RadioTransport` from `:core` with [JuulLabs Kable](https://github.com/JuulLabs/kable) (`com.juul.kable:kable-core`, version pinned in `gradle/libs.versions.toml`). Kable wraps Android `BluetoothGatt`, Apple CoreBluetooth and, on the JVM, its btleplug backend.

## Supported targets

| Target | Backend | Requirements |
|---|---|---|
| Android | `BluetoothGatt` | API 26 (`androidMinSdk`). API 31 and later need `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; API 34 and later need a [foreground service of type `connectedDevice`](../docs/architecture/android-platform-constraints.md) to keep the link alive in the background. |
| iOS | CoreBluetooth | `NSBluetoothAlwaysUsageDescription` in `Info.plist`. |
| JVM (macOS) | CoreBluetooth through btleplug | none beyond OS Bluetooth permission |
| JVM (Linux) | BlueZ over D-Bus through btleplug | BlueZ 5 and access to the system bus (typically the `bluetooth` group) |
| JVM (Windows) | WinRT Bluetooth APIs through btleplug | Windows 10 or later |

## GATT services and characteristics

The firmware exposes one primary service (see `BleConstants` and `docs/protocol.md` §3):

| Role | UUID | GATT op |
|---|---|---|
| Mesh service (scan filter) | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` | none |
| `fromradio` | `2c55e69e-4993-11ed-b878-0242ac120002` | READ: one full `FromRadio` per read; an empty read means drained |
| `toradio` | `f75c76d2-129e-4dad-a1dd-7866124401e7` | WRITE (acknowledged): one full `ToRadio` per write; firmware declares `CHR_PROPS_WRITE` only |
| `fromnum` | `ed9da18c-a800-4f66-a670-aa7547e34453` | NOTIFY and READ: 4-byte little-endian counter, used only as a wake signal; the transport always drains `fromradio` to empty |
| `logradio` (optional) | `5a3d6e49-06e6-4423-9944-e9de8cdf9547` | NOTIFY and READ: one `LogRecord` protobuf per notification; not part of the handshake, read through `BleTransport.deviceLogs()` |

GATT message boundaries replace the stream header (`0x94 0xC3 LEN_HI LEN_LO`) that TCP and serial use. The transport strips the 4-byte header on send and adds one on receive, so the engine's `WireCodec` stays transport-agnostic.

## MTU and connection priority

Every GATT operation carries exactly one `ToRadio` or `FromRadio`. On Android, the factory functions (`BleTransport(radio)`, `BleTransport(address)` and `BleTransport(device)`) request an ATT MTU of 517 and high connection priority after connect, then drop to balanced priority after 30 seconds. Both requests are best effort. The plain constructor `BleTransport(peripheral, address)` leaves MTU and priority to Kable and the OS; a host that needs a specific MTU calls `Peripheral.requestMtu(...)` before passing the peripheral in.

## Bonding and pairing

`BleTransport.connect()` goes through an explicit `TransportState.Bonding` step (see [`TransportState`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) and [`docs/SPEC.md`](../docs/SPEC.md)):

```text
Disconnected → Connecting → Bonding → Connected
```

After the link-layer connect, the transport reads the encrypted `fromradio` characteristic once. On Apple platforms, and on firmware that requires encryption, that read raises the OS pairing dialog. While it is outstanding the state is `Bonding`, so a UI can ask the reader to confirm pairing on the device. The engine's handshake clock starts only at `Connected`. The read times out after 120 seconds; the transport then moves to `Connected` anyway, so a stuck bond surfaces as a handshake failure rather than a silent stall. The Android 14 foreground-service requirement is in [`docs/integration-guide.md`](../docs/integration-guide.md#android-background-work), and the transport and engine threading contract in [ADR-012](../docs/decisions/012-transport-threading.md).

## Key packages

- `org.meshtastic.sdk.transport.ble`: `BleTransport(peripheral: Peripheral, address: String, parentContext = EmptyCoroutineContext)`. `address` is the storage identity (Android MAC address, iOS CoreBluetooth UUID).
- `BleScanner.scan(): Flow<DiscoveredRadio>`: a cold scan filtered on `BleConstants.MESH_SERVICE_UUID` that stops when the collector cancels. Every advertisement is emitted without deduplication, so a UI keeps RSSI current by keying on `DiscoveredRadio.address`. On BlueZ the first results are devices the host already knows, with a `null` RSSI. The caller holds the permissions (Android 12 and later `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`, older Android location, iOS `NSBluetoothAlwaysUsageDescription`); a missing one fails the flow with `MeshtasticException.Transport`. `BleTransport(radio: DiscoveredRadio)` connects to a result on every target, and on Android `BleTransport(address = radio.address)` works too.
- `bleAdvertisementMatches(needle, name, peripheralName, identifier)`: the substring match behind the `ble:NEEDLE` spec of `TransportTarget`. It compares the advertised name, the peripheral name and the platform identifier, and a MAC needle also matches a BlueZ D-Bus object path.
- `bondedBleDevices(context)` (Android only): the host's bonded devices as `BondedBleDevice(name, address)`, without a scan. It lists every bonded device, because Android exposes no reliable Meshtastic marker, and a bond doesn't mean the node is in range. It needs `BLUETOOTH_CONNECT` on API 31 and later, which the SDK doesn't request. `BleTransport(device)` turns an entry into a transport. iOS has no bonded list and the JVM backends expose none.
- `BleResilience`: opt-in Android link recovery, passed as `resilience` to `BleTransport(radio, ...)` or `BleTransport(address | device, resilience)`. Everything is off by default and ignored on iOS and the JVM.
  - `bondFirst` bonds before the GATT connect, publishing `TransportState.Bonding` with a 30-second limit; a refused bond fails the connect.
  - `autoConnectFallback` retries with `autoConnect = true` when a direct connect hasn't completed in 15 seconds; the next `connect()` starts direct again.
  - `refreshStaleGattCache` calls the hidden `BluetoothGatt.refresh()` (reflection into Kable's private fields, kept by `consumer-rules.pro`) and reconnects once when discovery lacks the Meshtastic service or one of its characteristics. A refused refresh leaves the connect unchanged.
  - The write type stays write-with-response: firmware declares `toradio` as `CHR_PROPS_WRITE` only (`NRF52Bluetooth.cpp`, `NimbleBluetooth.cpp`).
- `BleTransport.deviceLogs(): Flow<LogRecord>`: opt-in device logs from the `logradio` characteristic. The flow is cold: it subscribes while collected and unsubscribes on cancel, so collect again after a reconnect. It buffers 64 records and drops the oldest, so a slow collector never backpressures the link. Firmware streams only while `security.debug_log_api_enabled` is on. Undecodable or MTU-truncated notifications are skipped. The legacy text characteristic (`6c6fd238-...`) isn't read, since the firmware doesn't create it. This is separate from the PhoneAPI `FromRadio.log_record` path.
- `BleConstants`: the service and characteristic UUIDs as `kotlin.uuid.Uuid`, and prebuilt Kable `Characteristic` handles.
