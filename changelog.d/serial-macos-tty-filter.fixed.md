- `JvmSerialPorts.list()` now omits the `tty.*` duplicates of `cu.*` serial ports on macOS, as documented;
  the filter had tested for a `/dev/` prefix the port names do not carry.
