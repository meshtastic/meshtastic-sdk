- A desktop `BleTransport(address)` whose deferred scan times out after `disconnect()` stays
  `Disconnected` and fails the abandoned `connect()` as cancelled, instead of publishing a
  recoverable `TransportState.Error`.
