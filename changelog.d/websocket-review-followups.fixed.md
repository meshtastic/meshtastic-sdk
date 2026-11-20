- `WebSocketTransport.connect()` no longer hangs when its attempt is cancelled before it starts,
  closes the session when it is cancelled just after the upgrade, cancels an earlier concurrent
  attempt atomically, and reports a message the engine rejects as too big as the same
  non-recoverable error as the SDK's own size check.
