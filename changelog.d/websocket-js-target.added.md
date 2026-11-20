- Kotlin/JS target for `:transport-websocket` (`org.meshtastic:sdk-transport-websocket-js`), so a
  browser client can use `WebSocketTransport` over the platform `WebSocket` through Ktor's JS
  engine. TLS trust and mixed-content rules are the browser's. `commonTest` runs under Node.js.
