- `RadioTransportProvider` and `RadioClient.Builder.transport(identity, provider)`: auto-reconnect
  asks the provider for the transport of each attempt, after disconnecting the previous one. It
  can return the same instance or a fresh one. The attempt is counted before `open()` runs: waiting
  in `open()` until a connection is possible uses up no further attempt, while throwing from it
  fails that attempt as a failed `connect()` would.
- `AutoReconnectConfig.stableAfter`: the attempt count and backoff start over only after a session
  stays connected that long, so a radio that drops right after its handshake keeps backing off.
- `AutoReconnectConfig.maxHandshakeRecoveries` bounds handshake, liveness, protocol and receive
  overflow restarts in a row.
- `ConnectionState.Reconnecting.retryAt` says when the next attempt starts, and
  `RadioClient.retryNow()` starts it at once, from `Reconnecting` or `DeviceSleeping`.
- `BleResilience.refreshGattCacheAfterFailures` refreshes Android's GATT cache once per streak of
  that many failed connects or links that dropped within 5 s; a link that stays up re-arms it.
