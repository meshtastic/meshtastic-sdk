- Admin calls that fail with `NO_ROUTE`, `GOT_NAK` or `MAX_RETRANSMIT` return
  `AdminResult.Failed(error)` with that `Routing.Error` instead of `AdminResult.NodeUnreachable`,
  which now means the link was lost. Code matching `NodeUnreachable` for routing failures must
  match `Failed` as well.
