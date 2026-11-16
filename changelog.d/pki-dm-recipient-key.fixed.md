- `sendDirectMessageEncrypted` fills `public_key` with the recipient's stored key, as the
  Android app does. Firmware rejects a packet whose `public_key` differs from its own copy.
