# Module transport-websocket

WebSocket transport for the Meshtastic KMP SDK. It implements `RadioTransport` from `:core` over the firmware's WebSocket phone API, using the [Ktor client](https://ktor.io/docs/client-websockets.html).

The node side is in open firmware pull requests: ESP32 needs [firmware#12412](https://github.com/meshtastic/firmware/pull/12412), meshtasticd needs [firmware#12413](https://github.com/meshtastic/firmware/pull/12413), and a plain `ws://` port on meshtasticd needs the `HTTPPort` from [firmware#12414](https://github.com/meshtastic/firmware/pull/12414). The ESP32 build also needs the `esp32_https_server` fix in [meshtastic/esp32_https_server#13](https://github.com/meshtastic/esp32_https_server/pull/13), or a client that sends WebSocket pings is disconnected.

## Supported targets

| Target | Ktor engine |
|---|---|
| Android (API 26+, `INTERNET` permission) | CIO |
| JVM (macOS, Linux, Windows) | CIO |
| iOS (arm64, x64, Simulator arm64) | Darwin |
| JS (browser, Node.js) | Js (platform `WebSocket`) |
| Wasm (browser, Node.js) | Js (platform `WebSocket`) |

## Connection behavior

- **Endpoint:** `ws://host[:port]/api/v1/ws` or `wss://...`. A URL with a blank or `/` path maps to `/api/v1/ws`. ESP32 serves it on its web server (80/443); meshtasticd on its web server port (TLS, self-signed certificate) and, with firmware#12414, an optional plain `HTTPPort`.
- **Framing:** one `ToRadio` or `FromRadio` protobuf per binary message, with no stream header. Frames at the `RadioTransport` boundary still carry the 4-byte header: it is stripped on send and added on receive. A send over 512 payload bytes throws `MeshtasticException.Transport` instead of letting the node close with 1009. Text messages are ignored.
- **No subprotocol, no pings:** the transport offers no `Sec-WebSocket-Protocol` (meshtasticd answers 400 to one) and sends no WebSocket ping frames. The engine's 30-second heartbeat keeps the session under the node's 15-minute idle close.
- **Identity:** `ws:<scheme>://<host lowercased>:<port><path>`, with the default port and path made explicit. `TransportSpec` has no WebSocket case.
- **Failures at connect:** `connect()` throws `WebSocketTransportException`, a `MeshtasticException.Transport`, naming the cause: host unreachable, upgrade refused with its HTTP status, or an untrusted certificate. On JVM, Android and iOS a refused upgrade sets `httpStatus` (404 when the firmware has no WebSocket phone API at the path). In a browser (JS, Wasm) the platform `WebSocket` withholds the status, so `httpStatus` is always `null` there and a refused upgrade reads as unreachable. A `wss://` node with a self-signed certificate needs `ws://` (meshtasticd's plain `HTTPPort`) or a host `HttpClient` that trusts it.

## Browser hosts

On JS and Wasm the transport uses the platform `WebSocket`, so TLS trust and mixed-content rules are the browser's. An `https` page can't open `ws://` to another host, and a self-signed `wss://` node must be trusted in the browser first. `ws://localhost` from an `https` page is allowed, which fits meshtasticd's plain `HTTPPort` ([firmware#12414](https://github.com/meshtastic/firmware/pull/12414)). The 512-byte send check applies. Ktor's JS engine can't cap the frame size, so a received message over 512 bytes is read in full before it is rejected: the session closes with 1009 as on other targets, but the oversized message is held in memory first.

## Session end

The node serves one client at a time. When a session ends the transport publishes `TransportState.Error` whose `cause` is a `WebSocketTransportException`; read its `closeCode`, `closeReason` and `slotContested` rather than the message:

| Close | Meaning | `recoverable` |
|---|---|---|
| 4001 | another client took the node's only WebSocket slot | `false` |
| 1009 | message over 512 bytes | `false` |
| 1013 | node out of memory, try again later | `true` |
| 1000 | normal close, including the 15-minute idle limit | `true` |
| no close frame | dropped connection; `closeCode` is 1006 or `null` | `true` |

The transport never reconnects; that belongs to the engine's `AutoReconnectConfig`. Two firmware behaviors are tracked across `connect()` calls on one instance:

- **Contested slot:** ESP32 aborts a displaced client without a close frame when it can't write, so a 4001 may never arrive. After five sessions in a row that open and then end without a close frame within 30 seconds, the end is `recoverable = false` with `slotContested = true`. A session of 30 seconds or more, an end with a close frame, or `disconnect()` resets the count; a connect that never opened doesn't count.
- **1013:** the next `connect()` waits until 30 seconds after that session ended, staying in `Connecting`; `disconnect()` ends the wait.

On JVM, Android and iOS the SDK-created client times out a connect after 30 seconds. An ESP32 node drops a client that stops reading for 10 seconds, which arrives as a dropped connection.

## Custom client

`WebSocketTransport(url, httpClient)` takes a host-supplied Ktor `HttpClient`, for example one that trusts a self-signed certificate. The client needs the WebSockets plugin installed and no ping interval. The transport never closes a client the host passed in; it closes the one it created on `disconnect()`.

## Key packages

- `org.meshtastic.sdk.transport.websocket`: `WebSocketTransport(url: String, httpClient: HttpClient? = null)` and `WebSocketTransportException`, the typed error it reports.
