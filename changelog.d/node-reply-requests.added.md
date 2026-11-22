- `RoutingApi.requestPosition(node, channel, position, requestId)` and
  `RoutingApi.requestNodeInfo(node, channel, requestId)` send the requests of
  `RadioClient.requestPosition` and `RadioClient.requestNodeInfo` and return the reply as
  `AdminResult<Position>` and `AdminResult<User>`, matched by `request_id`, port and sender.
  Firmware leaves some requests unanswered, so `Timeout` is expected there; the KDoc lists the cases.
