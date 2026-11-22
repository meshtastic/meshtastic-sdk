- The handshake sends `want_config_id = 69420` once per session instead of again after 10 s
  without `my_info`: firmware's `handleStartConfig` has no re-entry guard, and the Android app
  dropped the same resend after it crashed a T-Beam on 2.7.25. A silent Stage 1 still ends in
  `HandshakeTimeout` at 20 s.
- Stage 2 fails only after a 30 s progress window with no frame, or after 600 s in total, instead
  of after 60 s in total, so a large NodeDB over slow BLE completes. 600 s matches the Android
  app's ceiling (10 times its 60 s Stage 2 budget).
- A local `get_owner_request` that gets no reply within 10 s no longer fails `connect()` with
  `HandshakeTimeout("Seeding Session")`: the session goes `Connected` without the local passkey and
  emits `ProtocolWarning("session passkey seed timed out")`. Local admin writes need no passkey.
