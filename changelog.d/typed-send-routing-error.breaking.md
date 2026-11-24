- A send a peer refuses with `Routing.Error.GOT_NAK` now fails with `SendFailure.Other(GOT_NAK)`
  instead of `SendFailure.NoRoute`, and a setter acked with `GOT_NAK` returns
  `AdminResult.Failed(GOT_NAK)` instead of `Failed(NO_ROUTE)`.
