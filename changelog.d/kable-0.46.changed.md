- Kable 0.46.0. Android requests the 517-byte ATT MTU through Kable's connect-time `PeripheralBuilder.mtu`, and the
  stale GATT cache refresh uses Kable's `refreshGattCache` instead of reflection, so the AAR no longer ships keep rules
  for Kable internals.
