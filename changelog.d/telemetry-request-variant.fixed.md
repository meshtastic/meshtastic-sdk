- `TelemetryApi` requests now name the variant they want with one empty submessage, set
  `Data.dest` and go out on the node's heard channel with the device hop limit. Firmware
  answers no request whose `Telemetry` names no variant, so these RPCs used to time out.
