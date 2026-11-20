# Module transport-websocket

WebSocket transport for the Meshtastic KMP SDK. Implements `RadioTransport` from `:core` over
firmware's WebSocket phone API using the [Ktor client](https://ktor.io/docs/client-websockets.html).

Node support needs firmware PRs that are not merged yet: ESP32 needs
[firmware#12412](https://github.com/meshtastic/firmware/pull/12412), meshtasticd needs
[firmware#12413](https://github.com/meshtastic/firmware/pull/12413), and a plain `ws://` port on
meshtasticd needs the `HTTPPort` from [firmware#12414](https://github.com/meshtastic/firmware/pull/12414).
The ESP32 build also needs the `esp32_https_server` fix in
[meshtastic/esp32_https_server#13](https://github.com/meshtastic/esp32_https_server/pull/13), or a
client that sends WebSocket pings is disconnected.

## Supported targets

| Target | Ktor engine |
|---|---|
| Android (API 26+, `INTERNET` permission) | CIO |
| JVM (macOS, Linux, Windows) | CIO |
| iOS (arm64, x64, Simulator arm64) | Darwin |
| JS (browser, Node.js) | Js (platform `WebSocket`) |

## Connection behavior

- **Endpoint:** `ws://host[:port]/api/v1/ws` or `wss://...`. A URL with a blank or `/` path is
  mapped to `/api/v1/ws`. ESP32 serves it on its web server (80/443); meshtasticd on its web server
  port (TLS, self-signed certificate) and, with firmware#12414, an optional plain `HTTPPort`.
- **Framing:** one `ToRadio` or `FromRadio` protobuf per binary message and no stream header.
  Frames at the `RadioTransport` boundary still carry the 4-byte header: it is stripped on send
  and prepended on receive. A send over 512 payload bytes throws `MeshtasticException.Transport`
  instead of letting the node close with 1009. Text messages are ignored.
- **No subprotocol, no pings:** the transport offers no `Sec-WebSocket-Protocol` (meshtasticd
  answers 400 to one) and sends no WebSocket ping frames. The engine's 30 second heartbeat keeps
  the session under the node's 15 minute idle close.
- **Identity:** `ws:<scheme>://<host lowercased>:<port><path>` with the default port and path made
  explicit. `TransportSpec` has no WebSocket case.
- **Failures at connect:** `connect()` throws `MeshtasticException.Transport` naming the cause:
  host unreachable, upgrade refused with the HTTP status, or an untrusted certificate. A `wss://`
  node with a self-signed certificate needs `ws://` (meshtasticd's plain `HTTPPort`) or a host
  `HttpClient` that trusts it.

## Browser hosts

On JS the transport uses the platform `WebSocket`, so TLS trust and mixed-content rules are the
browser's: an `https` page cannot open `ws://` to another host, and a self-signed `wss://` node must
be trusted in the browser first. `ws://localhost` from an `https` page is allowed, which fits
meshtasticd's plain `HTTPPort` ([firmware#12414](https://github.com/meshtastic/firmware/pull/12414)).
The 512 byte send check and the 1024 byte receive bound still apply.

## Session end

The node serves one client at a time. When a session ends the transport publishes
`TransportState.Error`:

| Close | Meaning | `recoverable` |
|---|---|---|
| 4001 | another client took the node's only WebSocket slot | `false` |
| 1009 | message over 512 bytes | `false` |
| 1013 | node out of memory, try again later | `true` |
| 1000 | normal close, including the 15 minute idle limit | `true` |
| no close frame | dropped connection | `true` |

The transport never reconnects; that belongs to the engine's `AutoReconnectConfig`. A node that
stops being read for 10 seconds drops the client (ESP32), which arrives as a drop.

## Custom client

`WebSocketTransport(url, httpClient)` takes a host-supplied Ktor `HttpClient`, for example one that
trusts a self-signed certificate. The client needs the WebSockets plugin installed and no ping
interval. A client the host passes is never closed by the transport; one the transport creates is
closed on `disconnect()`.

## Key packages

- `org.meshtastic.sdk.transport.websocket` - `WebSocketTransport(url: String, httpClient: HttpClient? = null)`,
  the single public type.
