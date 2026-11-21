- `RoutingApi.traceRoute(dest, hopLimit, channel, requestId)`: `hopLimit` is nullable and defaults
  to the device's `lora.hop_limit`; `DEFAULT_HOP_LIMIT` stays. `requestNeighborInfo` gains
  `channel` and `requestId`. Both accept a caller-chosen packet id. A class implementing
  `RoutingApi` must match the new signatures; `FakeRadioTransport.injectRouteReply` now sends the
  reply on `TRACEROUTE_APP`.
