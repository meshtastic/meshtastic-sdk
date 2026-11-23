- `WebSocketTransport.disconnect()` keeps the count of quick drops when the session had already
  ended with an error, as it has when the engine disconnects before reconnecting, so a contested
  slot is reported while auto-reconnect runs. Disconnecting a live session still resets it.
