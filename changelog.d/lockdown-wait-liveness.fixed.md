- A handshake waiting for a lockdown unlock now sends heartbeats and runs the liveness watchdog,
  so a half-open TCP socket fails the waiting `connect()` with `Transport("liveness timeout")`
  after 60 s of silence instead of waiting forever. Firmware answers heartbeats on a link it has
  not authorized.
