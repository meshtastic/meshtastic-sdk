# Module transport-tcp

TCP transport for the Meshtastic KMP SDK. It implements `RadioTransport` from `:core` with [Ktor Network](https://ktor.io/docs/client-sockets.html) (`io.ktor:ktor-network`). It needs no permissions beyond network access, which makes it the usual starting point for development, tests and the `samples/cli` runner.

## Supported targets

| Target | Backend | Requirement |
|---|---|---|
| Android | Ktor `aSocket().tcp()` | API 26 (`androidMinSdk`) and the `INTERNET` permission |
| JVM (macOS, Linux, Windows) | Ktor `aSocket().tcp()` over NIO | JDK 17 runtime |
| iOS (arm64, x64, Simulator arm64) | Ktor `aSocket().tcp()` over POSIX sockets | none |

## Connection behavior

- **Default port:** `4403` (the PhoneAPI port; see `docs/protocol.md` §1).
- **Framing:** stream framing per `protocol.md` §2: `0x94 0xC3 LEN_HI LEN_LO PAYLOAD`, big-endian 16-bit length, payload up to 512 bytes. The reader resynchronizes after mid-frame garbage (`SCAN_FOR_START1 → EXPECT_START2 → READ_LEN_HI → READ_LEN_LO → READ_PAYLOAD`).
- **Wake bytes:** on connect the transport writes `4 × 0x94` before any other traffic, as Meshtastic-Android's `StreamTransport.connect()` does. A healthy framer ignores them; a framer left mid-frame by an unclean session resets. See `protocol.md` §2 "Wake bytes".

## Idle timeout

Firmware closes a TCP connection after 15 minutes (`900_000 ms`) with no `ToRadio` from the client; traffic from the node does not reset the timer, and the timeout is fixed in firmware ([`docs/protocol.md` §1A](../docs/protocol.md#-1a-tcp-idle-timeout-behavior)). Once connected, the engine sends a `ToRadio` heartbeat every 30 seconds, so a live session never reaches it. If the node closes the socket anyway, Ktor reports EOF and the transport publishes `TransportState.Error(recoverable = true)`.

## Reconnection

`TcpTransport` holds no state across connects: `connect()` can be called again after `disconnect()` or a recoverable error. The transport never reconnects on its own. The engine's supervisor does, when `RadioClient.Builder.autoReconnect(...)` enables it (off by default): exponential backoff from 1 s, doubling, capped at 60 s, with jitter. A host that keeps reconnect policy itself should retry only `recoverable = true` errors.

## Discovery

Nodes with Wi-Fi or Ethernet advertise `_meshtastic._tcp` on port `4403` over mDNS, with the TXT records `shortname`, `id` and `pio_env` (firmware `WiFiAPClient.cpp`). `TcpDiscovery.discover()` is a cold `Flow<TcpDiscoveryEvent>`: `Found(DiscoveredTcpRadio)` when a service resolves, again when its address or TXT records change, and `Lost(serviceName)` when it disappears. Collecting starts browsing and cancelling stops it. The events are deltas, not a snapshot list, so a picker handles each node once.

```kotlin
val radio = (JvmTcpDiscovery().discover().first() as TcpDiscoveryEvent.Found).radio
val transport = radio.toTransport() // TcpTransport(host, port)
```

| Target | Class | Host requirements |
|---|---|---|
| JVM | `JvmTcpDiscovery(bindAddress = null)` | a multicast-capable LAN interface; pass `bindAddress` on multi-NIC hosts |
| Android | `AndroidTcpDiscovery(context)` (`NsdManager`) | `INTERNET`; `NsdManager` needs no multicast lock; `ACCESS_LOCAL_NETWORK` where enforced |
| iOS | `IosTcpDiscovery()` (`NSNetServiceBrowser`) | `Info.plist` keys `NSLocalNetworkUsageDescription` and `NSBonjourServices` = `_meshtastic._tcp` |

The JVM backend uses JmDNS (`org.jmdns:jmdns`, Apache-2.0), a JVM-only dependency that pulls in `slf4j-api`; without an SLF4J binding it logs one warning and discards JmDNS logs. Link-local IPv6 addresses are dropped, since dialing one needs a scope ID. The iOS backend is compile-checked only; see `docs/manual-tests.md` A7.

## Key packages

- `org.meshtastic.sdk.transport.tcp`: `TcpTransport(host: String, port: Int = 4403)`. It publishes `state: StateFlow<TransportState>`, emits inbound frames from `frames(): Flow<Frame>` (single collector, drained by the engine) and takes outbound frames through `suspend fun send(frame: Frame)`. Discovery shares the package: `TcpDiscovery`, `TcpDiscoveryEvent`, `DiscoveredTcpRadio` (with `toTransport()` and `toTransportSpec()`), and the platform classes `JvmTcpDiscovery`, `AndroidTcpDiscovery` and `IosTcpDiscovery`.
