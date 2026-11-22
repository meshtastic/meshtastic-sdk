- `MeshEvent.SecurityWarning.PublicKeyMismatch(node, offeredKey)`: a `NODEINFO_APP` offering a
  key different from the stored 32-byte key is still dropped, and is now reported once per
  node and offered key.
- `MeshEvent.KeyVerification` is emitted for the three `ClientNotification` key-verification
  payloads, next to the raw `MeshEvent.Notification`. With `AdminApi.keyVerification` a host
  can run the whole exchange.
