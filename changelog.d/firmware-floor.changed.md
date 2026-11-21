- `connect()` throws `MeshtasticException.FirmwareTooOld` when the device reports firmware below
  2.3.15 (`DeviceVersion.ABS_MIN_SUPPORTED`), instead of continuing into a handshake it may
  not complete. Auto-reconnect does not retry it. Firmware from 2.3.15 up to 2.5.13 (below
  `DeviceVersion.MIN_SUPPORTED`, 2.5.14) still connects and emits one
  `MeshEvent.ProtocolWarning` per session recommending an update. A missing or unparseable
  version still connects.
