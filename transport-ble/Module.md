# Module transport-ble

Bluetooth Low Energy transport for the Meshtastic KMP SDK. Implements the `RadioTransport`
interface from `:core` using [JuulLabs Kable](https://github.com/JuulLabs/kable) (currently
`com.juul.kable:kable-core:0.42.0`) for KMP BLE access. Kable provides a single API over
Android `BluetoothGatt`, iOS/macOS CoreBluetooth, and JVM `bluez` / Windows BLE stacks.

## Supported targets and OS versions

| Target | Backend | Minimum OS |
|---|---|---|
| Android | `BluetoothGatt` (via Kable) | Android 8.0 (API 26 — repo `androidMinSdk`). On API 31+ require `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`; on API 34+ a [foreground service of type `connectedDevice`](../docs/architecture/android-platform-constraints.md) is required to keep the link alive in the background. |
| iOS | CoreBluetooth | iOS 13 (Kable 0.42 baseline). `NSBluetoothAlwaysUsageDescription` must be set in `Info.plist`. |
| macOS (JVM) | CoreBluetooth via JNA | macOS 11+. |
| JVM (Linux) | `bluez` 5.x via DBus | Linux distros with `bluez >= 5.50` and the user in the `bluetooth` group. |
| JVM (Windows) | WinRT `Bluetooth.Advertisement` / `Bluetooth.GenericAttributeProfile` | Windows 10 (build 1709) or later. |

## GATT services and characteristics

The Meshtastic firmware exposes a single primary service with three characteristics
(see `BleConstants` and `docs/protocol.md` §3):

| Role | UUID | GATT op |
|---|---|---|
| Mesh service (scan filter) | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` | — |
| `fromradio` | `2c55e69e-4993-11ed-b878-0242ac120002` | READ — one full `FromRadio` per read; empty buffer means drained |
| `toradio` | `f75c76d2-129e-4dad-a1dd-7866124401e7` | WRITE (acknowledged) — one full `ToRadio` per write; firmware declares `CHR_PROPS_WRITE` only |
| `fromnum` | `ed9da18c-a800-4f66-a670-aa7547e34453` | NOTIFY+READ — 4-byte LE wake counter; treat as a wake signal only and always drain `fromradio` to empty |
| `logradio` (optional) | `5a3d6e49-06e6-4423-9944-e9de8cdf9547` | NOTIFY+READ — one `LogRecord` protobuf per notification; not part of the handshake. Read through `BleTransport.deviceLogs()` |

GATT message boundaries replace the stream framer (`0x94 0xC3 LEN_HI LEN_LO`) used by TCP/serial:
the transport strips the 4-byte header on send and synthesises one on receive so the engine's
`WireCodec` can stay transport-agnostic.

## MTU negotiation

MTU is delegated to Kable and the underlying OS BLE stack. The transport does **not** issue
an explicit `requestMtu(...)` because every GATT operation already carries exactly one
`ToRadio` / `FromRadio` envelope — Kable / Android negotiate the largest MTU each side
supports during connect, and the firmware will fragment payloads larger than the negotiated
ATT MTU automatically. Hosts that need a specific MTU should call `Peripheral.requestMtu(...)`
themselves before passing the peripheral to `BleTransport`.

## Bonding / pairing flow

`BleTransport.connect()` drives an explicit `TransportState.Bonding` step (see
[`TransportState`](../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) and
[`docs/SPEC.md` §`TransportState`](../docs/SPEC.md)). The state machine is:

```
Disconnected → Connecting → Bonding → Connected
```

After Kable reports a link-layer connect, the transport performs a warmup `read` on the
encrypted `fromradio` characteristic. On Apple platforms and on firmware that requires
encryption, this is what triggers the OS pairing dialog. While the read is outstanding the
transport publishes `TransportState.Bonding` so callers can render a "Confirm pairing on
your device" UI. The engine's handshake clock does **not** start until the state advances
to `Connected`. A 120 s warmup timeout bounds the wait; if it fires the transport advances
to `Connected` anyway and a stuck bond surfaces as a downstream handshake failure rather
than a silent stall. Cross-references: [`docs/integration-guide.md` §4](../docs/integration-guide.md#ble)
for Android 14+ foreground-service requirement; [ADR-012](../docs/decisions/012-transport-threading.md)
for the transport ↔ engine threading contract.

## Key packages

- `org.meshtastic.sdk.transport.ble` — `BleTransport(peripheral: Peripheral, address: String)`;
  `address` is the storage identity (Android MAC, iOS CoreBluetooth UUID).
- `org.meshtastic.sdk.transport.ble.BleScanner` — `scan(): Flow<DiscoveredRadio>`, a cold scan filtered on
  `BleConstants.MESH_SERVICE_UUID` that stops when the collector cancels. Every advertisement is
  emitted (no dedup), so a UI keeps RSSI current by keying on `DiscoveredRadio.address`. The caller
  holds the permissions (Android 12+ `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, older Android location, iOS
  `NSBluetoothAlwaysUsageDescription`); a missing one fails the flow with `MeshtasticException.Transport`.
  `BleTransport(radio: DiscoveredRadio)` connects to a result on every target (Android also applies
  its MTU and connection-priority tuning); on Android `BleTransport(address = radio.address)` works too.
- `org.meshtastic.sdk.transport.ble.bondedBleDevices(context)` (Android only) — the host's bonded
  devices as `BondedBleDevice(name, address)`, no scan. It lists every bonded device, because Android
  gives no reliable Meshtastic marker, and a bond does not mean the radio is in range. Needs
  `BLUETOOTH_CONNECT` on API 31+ (not requested by the SDK). `BleTransport(device)` is the one-liner
  to a transport. iOS has no bonded list and the JVM backends expose none, so neither target has this.
- `org.meshtastic.sdk.transport.ble.BleResilience` — opt-in Android link recovery, passed as
  `resilience` to `BleTransport(radio, ...)` or `BleTransport(address | device, resilience)`; all off by
  default, ignored on iOS and JVM. `bondFirst` bonds before the GATT connect (publishing
  `TransportState.Bonding`, 30 s limit, a refused bond fails the connect). `autoConnectFallback` retries
  with `autoConnect = true` when a direct connect has not completed in 15 s; the next `connect()` starts
  direct again. `refreshStaleGattCache` calls the hidden `BluetoothGatt.refresh()` (reflection into
  Kable 0.45's private fields, kept by `consumer-rules.pro`) and reconnects once when discovery lacks
  the Meshtastic service or one of its characteristics; a refused refresh leaves the connect unchanged.
  The write type stays write-with-response: firmware declares `toradio` as `CHR_PROPS_WRITE` only
  (`NRF52Bluetooth.cpp:324`, `NimbleBluetooth.cpp:985`), so there is nothing to choose.
- `BleTransport.deviceLogs(): Flow<LogRecord>` — opt-in device logs from the `logradio` characteristic. Cold:
  it subscribes while collected and unsubscribes on cancel; collect again after a reconnect. Buffered
  (64, drop-oldest) so a slow collector never backpressures the link. Firmware streams only while
  `security.debug_log_api_enabled` is on. Undecodable or MTU-truncated notifications are skipped. The
  legacy text characteristic (`6c6fd238-...`) is not read: current firmware does not create it. Separate
  from the phone-API `FromRadio.log_record` path.
- `org.meshtastic.sdk.transport.ble.BleConstants` — service / characteristic UUIDs as `kotlin.uuid.Uuid`
  and pre-built Kable `Characteristic` handles.
