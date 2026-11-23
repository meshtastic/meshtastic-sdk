- The liveness watchdog arms only after the firmware has answered a heartbeat, and only while the
  engine sends periodic heartbeats on the link. Firmware before 2.7.4 sends no reply, so a quiet
  mesh dropped a healthy session every 60 s, and so did a BLE link with `disableBleHeartbeat()`.
