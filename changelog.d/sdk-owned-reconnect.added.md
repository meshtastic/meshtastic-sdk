- `RadioTransportProvider` and `RadioClient.Builder.transport(identity, provider)`: auto-reconnect
  asks the provider for the transport of each attempt, after disconnecting the previous one. It
  can return the same instance or a fresh one, and can suspend until a connection is possible
  without using up an attempt.
- `AutoReconnectConfig.stableAfter`: the attempt count and backoff start over only after a session
  stays connected that long, so a radio that drops right after its handshake keeps backing off.
- `AutoReconnectConfig.maxHandshakeRecoveries` bounds handshake, liveness and protocol restarts in
  a row; a completed handshake resets the count.
- `ConnectionState.Reconnecting.retryAt` says when the next attempt starts, and
  `RadioClient.retryNow()` starts it at once, from `Reconnecting` or `DeviceSleeping`.
- `BleResilience.refreshGattCacheAfterFailures` refreshes Android's GATT cache once per streak of
  that many failed connects or links that dropped within 5 s; a link that stays up re-arms it.
