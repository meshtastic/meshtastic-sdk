- `AdminApi.editSettings` and `batch` report a local commit as applied when the transport wrote
  it and the link then dropped. Firmware turns BLE off inside the commit handler, so over BLE no
  ACK ever arrives.
- Writes inside `editSettings` and `batch`, and their commit, go through the paced send queue, so
  a long transaction no longer overruns the device's three-packet BLE inbound queue.
