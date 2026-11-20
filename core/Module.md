# Module core

The transport-agnostic Meshtastic client engine. `RadioClient` connects to a node through any `RadioTransport`, runs the handshake, keeps the NodeDB, and exposes state as `StateFlow` and inbound traffic as `Flow`.

`:core` declares no in-tree project dependency (ADR-008). It depends on the published `org.meshtastic:protobufs` artifact, kotlinx-coroutines and Okio, all re-exported through `api`, plus kotlinx-datetime, atomicfu and Kermit. Transports (Bluetooth LE, TCP, serial, WebSocket) and persistent storage are separate modules wired in at build time.

What `RadioClient` covers:

- Sending: `send`, `sendText`, `sendReaction` and the `send { }` DSL, each returning a `MessageHandle`; `sendRaw` for a raw `ToRadio`.
- Receiving: `packets`, `events`, `nodes` and `fromRadio` (every decoded `FromRadio`, before the engine handles it); typed payload accessors (`asNodeStatus()`, `asPaxcount()` and the rest) for each port number.
- Sub-APIs: `admin` (config edits, with `admin.batch { }` for settings transactions), `telemetry`, `routing`, `files` (XModem download and upload) and `storeForward`. `adminSessions` reports the admin session state per node.
- Extensions on `RadioClient`: `exportDeviceProfile`, `installDeviceProfile` and `enableHamMode`.
- Helpers: the LoRa region table (`LoRaRegionInfo.forRegion`, `modemParams`), channel and contact URLs, `TransportTarget.parse` for `ble:`, `tcp:` and `serial:` spec strings, and `InMemoryStorageProvider` for hosts that persist nothing.

Key packages:
- `org.meshtastic.sdk`: the public API, including the transport and storage SPIs and the `LogSink` hook.
- `org.meshtastic.sdk.internal`: the engine actor (single writer, see ADR-002) and codec; not public API.
