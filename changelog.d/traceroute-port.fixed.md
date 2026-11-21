- `RoutingApi.traceRoute` sends on `TRACEROUTE_APP` with `want_ack`, `Data.dest`, the heard
  channel and the device hop limit, and resolves on the `TRACEROUTE_APP` reply. It sent a
  `Routing.route_request` on `ROUTING_APP`, which firmware never answers.
- A remote `requestNeighborInfo` carries `want_ack`, `Data.dest`, the heard channel and the device
  hop limit; without `want_ack` firmware kept a zero hop limit.
