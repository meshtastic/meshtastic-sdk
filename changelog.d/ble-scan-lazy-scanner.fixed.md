- `BleScanner.scan()` builds the platform scanner when the flow is collected, so a scanner that
  cannot be built fails the flow with `MeshtasticException.Transport` instead of throwing from
  `scan()` unwrapped.
