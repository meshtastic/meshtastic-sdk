- On Android, `BleTransport(address)` finds the radio by an address-filtered scan in every
  `connect()` and connects from its advertisement, as the Meshtastic Android app does. A connect
  by bare MAC failed while Android's device cache was empty, as after a Bluetooth restart, until
  the autoConnect fallback took over. A bonded radio gets one 5-second scan, then its bonded
  handle; an unbonded radio gets three 5-second scans a second apart, then `connect()` fails with
  `MeshtasticException.Transport`.
- The Android BLE transport waits 3 seconds, not 1, between a GATT cache refresh and the
  reconnect that rediscovers services.
