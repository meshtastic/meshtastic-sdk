- `JvmSerialPorts.open` fails with `MeshtasticException.Transport` for a port that does not
  exist, as `AndroidSerialPorts.open` does, instead of jSerialComm's
  `SerialPortInvalidPortException`.
