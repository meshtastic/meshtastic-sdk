- `AdminApi.withRpcTimeout(timeout)` returns an admin API whose calls wait up to `timeout`, for
  multi-hop remote reads.
- `RadioTransport.linkDropsOnRestartCommands`, true for `BleTransport`, marks links that firmware
  takes down inside restart-class admin handlers.
