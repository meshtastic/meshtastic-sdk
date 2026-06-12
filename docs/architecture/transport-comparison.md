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
| Backing library | Ktor `ktor-network` | JuulLabs Kable (`kable-core`) | jSerialComm |
| Typical use case | Dev / testing, LAN-attached routers, gateways, JVM samples | Phone ↔ handheld radio (the default consumer path) | USB-tethered desktop debugging, Android USB-OTG, headless setups |
| Latency | Lowest (native sockets) | Highest — GATT round-trips, optional bonding | Low (raw byte stream) |
| Throughput | Highest — TCP MSS / network-bound | Lowest — ATT MTU, acknowledged-write cadence | High — 115 200 baud (≈ 11 KB/s) |
| Requires hardware | None (just a routable host) | BLE radio on host **and** device | USB host port + USB-serial bridge IC |
| Multiple devices per host | Yes — one `RadioClient` per `(host, port)` | Yes — one `RadioClient` per peripheral | Yes — one `RadioClient` per port |
| Bonding / pairing | None | Yes — surfaced as [`TransportState.Bonding`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) on first encrypted read | None |
| Auto-discovery | None (host:port supplied by user) | Kable `Scanner` filtered on `BleConstants.MESH_SERVICE_UUID` | OS port enumeration via `JvmSerialPorts.list()` / `AndroidSerialPorts.list()` |
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
| Linux x64 native | ✓ | — | — |

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
WebSocket bridge, mock):

1. Create a new module (e.g. `:transport-foo`); depend only on `:core` and `:proto`.
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
