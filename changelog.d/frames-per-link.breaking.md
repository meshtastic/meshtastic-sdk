- The engine collects `RadioTransport.frames()` once per successful `connect()`, including each
  auto-reconnect, instead of once per engine. A custom transport that reconnects must return a
  flow for the current link on every call; one shared `consumeAsFlow()` fails on the second
  link. The bundled transports already do.
