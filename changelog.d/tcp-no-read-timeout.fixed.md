- `TcpTransport` no longer fails a read after 65 s of silence. Firmware before 2.7.4 sends nothing
  back for a heartbeat, so a healthy TCP link on a quiet mesh dropped every 65 s; the engine's
  liveness watchdog, armed by a heartbeat reply, covers a dead link on newer firmware.
