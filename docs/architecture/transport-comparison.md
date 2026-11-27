# Transport comparison

This page compares the four first-party transport modules so you can pick one per platform and use case. Each implements the [`RadioTransport`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) interface from `:core`, and the engine treats them all the same. [ADR-006](../decisions/006-multi-module-rationale.md) explains why they are separate modules, and [ADR-012](../decisions/012-transport-threading.md) sets the threading contract.

## Feature matrix

| Feature | `:transport-tcp` | `:transport-ble` | `:transport-serial` | `:transport-websocket` |
|---|---|---|---|---|
| Backing library | Ktor `ktor-network` | JuulLabs Kable (`kable-core`) | jSerialComm (JVM, Android); usb-serial-for-android (Android, host-supplied) | Ktor client: CIO on JVM and Android, Darwin on iOS, the platform `WebSocket` on JS and Wasm |
| Typical use case | Development and testing, LAN-attached nodes, meshtasticd, gateways, JVM samples | Phone or tablet to a handheld node | USB-tethered desktop tools, Android USB-OTG, headless setups | Wi-Fi nodes and meshtasticd, and browser hosts, which have no raw sockets |
| Latency | Lowest (native sockets) | Highest: GATT round trips, optional bonding | Low (raw byte stream) | Low (one binary message per frame) |
| Throughput | Highest: network-bound | Lowest: ATT MTU and acknowledged-write cadence | 115 200 baud (about 11 KB/s) | Network-bound |
| Requires hardware | None (a routable host) | Bluetooth on the host and the node | USB host port and a USB-serial bridge | None (a routable host) |
| Multiple nodes per host | Yes, one `RadioClient` per `(host, port)` | Yes, one `RadioClient` per peripheral | Yes, one `RadioClient` per port | Yes, one `RadioClient` per URL |
| Bonding and pairing | None | Surfaced as [`TransportState.Bonding`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) on first encrypted read | None | None; `wss://` needs a trusted certificate |
| Discovery | mDNS `_meshtastic._tcp` through `TcpDiscovery.discover()`: `JvmTcpDiscovery` (JmDNS), `AndroidTcpDiscovery` (`NsdManager`), `IosTcpDiscovery` (`NSNetServiceBrowser`). A known host and port works without it. | `BleScanner.scan()` (Kable `Scanner` filtered on `BleConstants.MESH_SERVICE_UUID`), then `BleTransport(radio)` | Port enumeration through `JvmSerialPorts.list()` and `findMeshtastic()`, `AndroidSerialPorts.list()`; USB host devices through `AndroidUsbSerialPorts.list(context)` | None; the host supplies the URL |
| Known nodes without a scan | None | Android: `bondedBleDevices(context)` lists every bonded device (not only nodes, and not proof of presence); `BleTransport(device)` connects. iOS and JVM have no such list. | `JvmSerialPorts.list()`, `AndroidSerialPorts.list()` | None |
| Link recovery | None beyond engine backoff | 3 GATT connect retries; Android opt-ins through `BleResilience`: bond first, `autoConnect` fallback, GATT cache refresh | None | Detects a contested slot (another client keeps taking the node) and waits 30 s after a 1013 close |
| Device logs | `FromRadio.log_record` | `BleTransport.deviceLogs()` reads the `logradio` characteristic (opt-in, drop-oldest) | `FromRadio.log_record`; debug text between frames through `onTextLine` | `FromRadio.log_record` |
| Idle behavior | 15-minute firmware idle close ([`protocol.md` §1A](../protocol.md#-1a-tcp-idle-timeout-behavior)), which the engine's 30 s heartbeat prevents | Kept alive by GATT; the OS may drop it when the node resets | None; the link ends only on cable detach | 15-minute idle close, which the heartbeat prevents |
| Wake-byte preamble | Yes (4× `0x94`) | Not applicable | Yes (4× `0x94`) | Not applicable |
| Module docs | [`transport-tcp/Module.md`](../../transport-tcp/Module.md) | [`transport-ble/Module.md`](../../transport-ble/Module.md) | [`transport-serial/Module.md`](../../transport-serial/Module.md) | [`transport-websocket/Module.md`](../../transport-websocket/Module.md) |

`TransportTarget.parse` reads the compact `ble:NEEDLE`, `tcp:HOST[:PORT]` and `serial:PORT[:BAUD]` forms a CLI flag or agent tool carries; opening the matching transport stays with the host. WebSocket has no spec form.

Node support for WebSocket needs firmware pull requests that are not merged yet: #12412 for ESP32 and #12413 for meshtasticd, plus #12414 for plain `ws://` on meshtasticd. The node serves one WebSocket client at a time; a newer client closes the older one with code 4001, reported as a non-recoverable `TransportState.Error`.

## OS support

| Target | TCP | BLE | Serial | WebSocket |
|---|:---:|:---:|:---:|:---:|
| Android (API 26+) | ✓ (`INTERNET`) | ✓ (`BLUETOOTH_SCAN`/`CONNECT` on API 31+; FGS on API 34+) | ✓ (USB Host and `USB_DEVICE_ATTACHED` intent filter; FGS on API 34+) | ✓ (`INTERNET`) |
| iOS 13+ | ✓ | ✓ (`NSBluetoothAlwaysUsageDescription`) | — (no general serial access) | ✓ |
| macOS (JVM) | ✓ | ✓ (CoreBluetooth through JNA) | ✓ (jSerialComm bundled `.dylib`) | ✓ |
| Linux (JVM) | ✓ | ✓ (BlueZ 5.x) | ✓ (jSerialComm bundled `.so`) | ✓ |
| Windows (JVM) | ✓ | ✓ (Windows 10 1709+) | ✓ (jSerialComm bundled `.dll`) | ✓ |
| Browser and Node.js (JS, Wasm) | — | ✓ in a browser with Web Bluetooth (secure page; the chooser needs a user gesture) | — | ✓ (browser TLS and mixed-content rules apply) |

Every module declares `jvm`, `android`, `iosArm64`, `iosX64` and `iosSimulatorArm64`. `:core`, `:testing`, `:transport-websocket` and `:transport-ble` also declare `js` and `wasmJs` ([ADR-017](../decisions/017-web-targets.md)). There are no macOS, Linux or Windows native targets.

Per-target Android and foreground-service detail: [`android-platform-constraints.md`](./android-platform-constraints.md).

## Choosing a transport

- For a sample, CLI or test, start with TCP: no permissions, and it works against meshtasticd and any Wi-Fi node.
- For a phone or tablet app, use BLE. Plan for `TransportState.Bonding` in the UI and add the Android 14+ foreground service.
- For desktop tooling, a bench setup or an Android USB-OTG accessory, use serial at 115 200 baud; let the user pick from `JvmSerialPorts.list()`.
- For a browser host, use WebSocket, or BLE where the browser has Web Bluetooth: `requestBleTransport()` opens its device chooser, since a page cannot scan.
- HTTP and MQTT are not first-party transports. HTTP is reserved as a `TransportSpec.Http` shape in `Transport.kt`, and MQTT is not a `RadioTransport` by design ([`protocol.md` §14](../protocol.md#14-mqtt-proxy-mode)).

## Custom transports

The `RadioTransport` contract is small and stable. To add a transport:

1. Create a module (for example `:transport-foo`) that depends only on `:core`, which re-exports the wire types.
2. Implement `RadioTransport` following [ADR-012](../decisions/012-transport-threading.md): never block the engine's coroutine context, deliver inbound bytes through a single `Flow<Frame>`, and expose a `StateFlow<TransportState>` that walks `Disconnected → Connecting → (Bonding)? → Connected → Disconnected | Error`.
3. Follow the [`transport-module-authoring`](../../.github/skills/transport-module-authoring/SKILL.md) skill for source-set layout, ABI rules and the test fakes in `:testing`.
4. Document supported OSes, framing and platform setup in a `Module.md` shaped like the existing ones.

## See also

- [ADR-006](../decisions/006-multi-module-rationale.md): why each transport is its own module.
- [`module-graph.md`](./module-graph.md): the full dependency graph.
- [ADR-002](../decisions/002-architecture.md): the engine actor invariant.
- [ADR-012](../decisions/012-transport-threading.md): the transport threading contract.
- [`docs/protocol.md`](../protocol.md): wire framing, idle timeout, BLE GATT layout.
