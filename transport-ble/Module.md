# Module transport-ble

Bluetooth Low Energy transport for the Meshtastic KMP SDK. Implements the `RadioTransport`
interface from `:core` using [JuulLabs Kable](https://github.com/JuulLabs/kable) (currently
`com.juul.kable:kable-core:0.45.0`, pinned in `gradle/libs.versions.toml`) for KMP BLE access. Kable provides a single API over
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

GATT message boundaries replace the stream framer (`0x94 0xC3 LEN_HI LEN_LO`) used by TCP/serial:
the transport strips the 4-byte header on send and synthesises one on receive so the engine's
`WireCodec` can stay transport-agnostic.

## MTU negotiation

Every GATT operation carries exactly one `ToRadio` / `FromRadio` envelope (up to 512 bytes),
so the ATT MTU has to be large enough for a whole frame. Android keeps the ATT MTU at the BLE
minimum (23, i.e. 20-byte writes) unless the central asks for more, and any `ToRadio` write
over 20 bytes then fails.

- **Android, `BleTransport(address, builderAction)` factory:** after each link establishment
  the transport calls `AndroidPeripheral.requestMtu(517)` (the Android maximum) and requests
  `Priority.High` for 30 s to speed up the handshake, then drops back to `Priority.Balanced`.
  Both are best-effort: a failure leaves the OS defaults in place and never fails the connect.
- **`BleTransport(peripheral, address)` constructor (all platforms):** no MTU request is made.
  On Android, a host that builds its own `Peripheral` and wants this tuning should use the
  `BleTransport(address)` factory, or request the MTU on the connected peripheral itself.

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

- `org.meshtastic.sdk.transport.ble` — `BleTransport(peripheral: Peripheral, address: String, parentContext: CoroutineContext = EmptyCoroutineContext)`. Caller
  obtains `Peripheral` from a Kable `Scanner` filtered on `BleConstants.MESH_SERVICE_UUID`;
  `address` is the storage identity (Android MAC, iOS CoreBluetooth UUID). On Android,
  `BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {})` builds the
  `Peripheral` from a MAC address and adds the MTU / connection-priority tuning above.
- `org.meshtastic.sdk.transport.ble.BleConstants` — service / characteristic UUIDs as `kotlin.uuid.Uuid`
  and pre-built Kable `Characteristic` handles.
