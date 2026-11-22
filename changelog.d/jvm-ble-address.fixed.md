- On the JVM, `BleTransport(address)` accepts a Bluetooth MAC as well as a saved
  `DiscoveredRadio.address`. A MAC on Linux used to crash Kable's native layer, which parses the
  identifier as JSON there; an address that is not a valid identifier for the host is now
  resolved by a scan of up to 15 seconds in the first `connect()`, which fails with
  `MeshtasticException.Transport` when no advertisement matches.
