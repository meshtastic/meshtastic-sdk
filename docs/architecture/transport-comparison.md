# Transport comparison

Cross-cuts the three first-party transport modules so consumers can pick the right one
per platform / use case. Each transport implements the same
[`RadioTransport`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt)
interface from `:core`; the engine treats them uniformly. See
[`transport-isolation.md`](./transport-isolation.md) for *why* they are separate modules
and [ADR-012](../decisions/012-transport-threading.md) for the threading contract.

## Feature matrix

| Feature | `:transport-tcp` | `:transport-ble` | `:transport-serial` |
|---|---|---|---|
| Backing library | Ktor `ktor-network` | JuulLabs Kable (`kable-core`) | jSerialComm (JVM, Android); usb-serial-for-android (Android, host-supplied) |
| Typical use case | Dev / testing, LAN-attached routers, gateways, JVM samples | Phone ↔ handheld radio (the default consumer path) | USB-tethered desktop debugging, Android USB-OTG, headless setups |
| Latency | Lowest (native sockets) | Highest — GATT round-trips, optional bonding | Low (raw byte stream) |
| Throughput | Highest — TCP MSS / network-bound | Lowest — ATT MTU, acknowledged-write cadence | High — 115 200 baud (≈ 11 KB/s) |
| Requires hardware | None (just a routable host) | BLE radio on host **and** device | USB host port + USB-serial bridge IC |
| Multiple devices per host | Yes — one `RadioClient` per `(host, port)` | Yes — one `RadioClient` per peripheral | Yes — one `RadioClient` per port |
| Bonding / pairing | None | Yes — surfaced as [`TransportState.Bonding`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) on first encrypted read | None |
| Auto-discovery | mDNS `_meshtastic._tcp` via `TcpDiscovery.discover()`: `JvmTcpDiscovery` (JmDNS), `AndroidTcpDiscovery` (`NsdManager`), `IosTcpDiscovery` (`NSNetServiceBrowser`); a known host:port still works without it | `BleScanner.scan()` (Kable `Scanner` filtered on `BleConstants.MESH_SERVICE_UUID`), then `BleTransport(radio)` | OS port enumeration via `JvmSerialPorts.list()` / `AndroidSerialPorts.list()`; USB host devices via `AndroidUsbSerialPorts.list(context)` |
| Known devices without a scan | None | Android: `bondedBleDevices(context)` lists every bonded device (not only radios, and not proof of presence); `BleTransport(device)` connects. iOS and JVM have no such list | `JvmSerialPorts.list()` / `AndroidSerialPorts.list()` |
| Link recovery | None beyond engine backoff | 3 GATT connect retries; Android opt-ins via `BleResilience`: bond-first, `autoConnect` fallback, GATT cache refresh | None |
| Device logs | Via the phone-API `FromRadio.log_record` | `BleTransport.deviceLogs()` reads the `logradio` characteristic (opt-in, drop-oldest) | Via the phone-API `FromRadio.log_record` |
| Idle behavior | **15 min** firmware-side idle close (`protocol.md` §1A) | Link kept alive by GATT; OS may drop on radio reset | None — link dies only on cable detach |
| Wake-byte preamble | Yes (4× `0x94`) | Not applicable (GATT message boundaries) | Yes (4× `0x94`) |
| Module docs | [`transport-tcp/Module.md`](../../transport-tcp/Module.md) | [`transport-ble/Module.md`](../../transport-ble/Module.md) | [`transport-serial/Module.md`](../../transport-serial/Module.md) |

## OS support

| Target | TCP | BLE | Serial |
|---|:---:|:---:|:---:|
| Android (API 26+) | ✓ (`INTERNET`) | ✓ (`BLUETOOTH_SCAN`/`CONNECT` on API 31+; FGS on API 34+) | ✓ (USB Host + `USB_DEVICE_ATTACHED` intent-filter; FGS on API 34+) |
| iOS 13+ | ✓ | ✓ (`NSBluetoothAlwaysUsageDescription`) | — (no general serial access) |
| macOS (JVM) | ✓ | ✓ (CoreBluetooth via JNA) | ✓ (jSerialComm bundled `.dylib`) |
| Linux (JVM) | ✓ | ✓ (`bluez` 5.x) | ✓ (jSerialComm bundled `.so`) |
| Windows (JVM) | ✓ | ✓ (Win10 1709+) | ✓ (jSerialComm bundled `.dll`) |

The SDK modules declare `jvm`, `android`, `iosArm64`, `iosX64` and `iosSimulatorArm64` targets; there are no macOS, Linux or Windows native targets.

## WebSocket

[`:transport-websocket`](../../transport-websocket/Module.md) connects to firmware's WebSocket
phone API (`ws://host[:port]/api/v1/ws` or `wss://`) on Ktor: CIO on JVM and Android, Darwin on
iOS. One protobuf rides in each binary message with no stream header, and the node serves one
client at a time (a newer client closes the old one with code 4001, reported as a non-recoverable
`TransportState.Error`). The 15 minute idle close is covered by the engine's 30 second heartbeat.
Node support needs firmware#12412 (ESP32) or #12413 (meshtasticd), both unmerged; plain `ws://` on
meshtasticd needs #12414's `HTTPPort`.

Per-target Android / FGS detail: [`android-platform-constraints.md`](./android-platform-constraints.md).

## Choosing a transport

- **Building a sample, CLI, or test** → start with TCP. No permissions, easiest to wire,
  works against `firmware --tcp` and any router.
- **Phone or tablet app talking to a Meshtastic radio** → BLE. Plan for the
  `TransportState.Bonding` state in your UI and add the Android 14+ foreground service.
- **Desktop tooling, in-lab bench setup, or Android USB-OTG accessory** → Serial. Pin
  baud at 115 200; let the user pick from `JvmSerialPorts.list()`.
- **HTTP / MQTT** → not first-party transports; HTTP is reserved as a `TransportSpec.Http`
  shape (see `Transport.kt`) and MQTT is intentionally **not** a `RadioTransport` per
  `docs/SPEC.md` §2.

## Custom transports

The `RadioTransport` contract is small and stable. To add a new transport (LoRa proxy,
mock):

1. Create a new module (e.g. `:transport-foo`); depend only on `:core` (which re-exports the wire types).
2. Implement `RadioTransport` honoring [ADR-012](../decisions/012-transport-threading.md):
   never block the engine's coroutine context; funnel inbound bytes through a single
   `Flow<Frame>`; expose a `StateFlow<TransportState>` that walks
   `Disconnected → Connecting → (Bonding)? → Connected → Disconnected | Error`.
3. Follow the [`transport-module-authoring`](../../.github/skills/transport-module-authoring/SKILL.md)
   skill — it covers source-set layout, ABI rules, and the test fakes in `:testing`.
4. Document supported OSes, framing, and any platform setup in a `Module.md` matching
   the structure of the three modules above.

## See also

- [`transport-isolation.md`](./transport-isolation.md) — module-boundary rationale and
  per-transport error semantics.
- [`module-graph.md`](./module-graph.md) — full dependency graph.
- [ADR-002](../decisions/002-architecture.md) — engine actor invariant.
- [ADR-012](../decisions/012-transport-threading.md) — transport-side threading contract.
- [`docs/protocol.md`](../protocol.md) — wire framing, idle timeout, BLE GATT layout.
