- On Android, `BleScanner.scan()` and the address scan in `BleTransport(address)` count scan starts and fail with
  `MeshtasticException.Transport` once five have started in 30 seconds, instead of waiting silently.
