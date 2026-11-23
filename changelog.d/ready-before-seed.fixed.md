- `connect()` reaches `Connected` at the Stage 2 `config_complete_id` and seeds the local session
  passkey afterwards, as the Android app does. Firmware replays every node's stored position and
  telemetry right after that completion, and while the engine held packets for the seed reply a
  large NodeDB overflowed the 64-packet handshake buffer, closing `sessionEvents` on every
  connect. A seed reply that arrives after `Connected` still latches the passkey.
