- A BLE link failure that lands after `disconnect()` no longer publishes a recoverable
  `TransportState.Error` over `Disconnected`; the abandoned `connect()` ends cancelled.
