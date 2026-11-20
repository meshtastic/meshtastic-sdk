- `WebSocketTransport` stops reporting a recoverable error after five sessions in a row that open and
  end without a close frame within 30 seconds, since another client is repeatedly taking the node's
  only WebSocket slot. The next `connect()` after a 1013 waits until 30 seconds after that session
  ended, and on JVM, Android and iOS the SDK-created client times out a connect after 30 seconds.
