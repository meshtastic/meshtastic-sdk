- `redacted()` on `MeshPacket`, `Config`, `ModuleConfig`, `Channel`, `ClientNotification` and `DeviceUIConfig`
  returns a copy with passwords, PSKs (beacon offers included), PINs, private keys, the admin session passkey and
  key-verification numbers cleared.
