- `WebSocketTransportException`, a `MeshtasticException.Transport` that `WebSocketTransport`
  throws from `connect()` and publishes as the cause of a session-ending `TransportState.Error`.
  It carries `closeCode`, `closeReason`, `httpStatus` for a refused upgrade and `slotContested`,
  so callers no longer parse the message. `MeshtasticException.Transport` is now open so a
  transport can subclass it.
