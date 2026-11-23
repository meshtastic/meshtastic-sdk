- `sessionEvents` delivers the events of a handshake, such as a `ClientNotification` the firmware
  queued while no client was connected or an MQTT client-proxy message, after the `Ready` snapshot
  in receive order with the held packets. They were dropped before.
