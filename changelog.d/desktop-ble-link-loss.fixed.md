- `BleTransport` reports a link the radio or OS drops as a recoverable `TransportState.Error`
  when Kable reports `Disconnecting` first, as its desktop backend does. It used to publish
  `Disconnected`, which the engine ignores, so a desktop session with the BLE heartbeat off stayed
  `Connected` on a dead link.
- A BLE connect attempt without `BleResilience.autoConnectFallback` now fails after 15 seconds
  and is retried, then fails with `MeshtasticException.Transport`. On the desktop an attempt
  could hang for minutes while Kable rescanned for a radio BlueZ had dropped.
