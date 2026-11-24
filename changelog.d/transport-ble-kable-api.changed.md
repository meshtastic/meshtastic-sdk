- `:transport-ble` exposes Kable as an `api` dependency, since its public API uses Kable types, so a
  consumer no longer adds `kable-core` to compile against `BleTransport`.
