- With auto-reconnect on, a handshake that times out or drops while a sleeping device's wake window
  is open is retried as part of the sleep, counted against neither `maxAttempts` nor
  `maxHandshakeRecoveries`; the device counts as asleep until a handshake reaches `Connected`.
