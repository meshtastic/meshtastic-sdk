- `RoutingApi.withRpcTimeout` and `TelemetryApi.withRpcTimeout`, matching `AdminApi.withRpcTimeout`, for a
  multi-hop request whose reply or routing error can outlive the client's `rpcTimeout`.
