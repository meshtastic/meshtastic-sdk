- A `rebooted` frame before the Stage 1 `my_info` ends the attempt with
  `MeshtasticException.Transport` and `MeshEvent.DeviceRebooted` when no `my_info` follows within
  2 s, instead of waiting out the 20 s Stage 1 timeout. Firmware before 2.8 sends it from the
  serial console at boot, so the request it answers went to the previous boot.
