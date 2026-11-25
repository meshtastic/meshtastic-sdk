- `SendFailure.routingError` returns the `Routing.Error` behind a failure, and
  `SendFailure.QueueRejected.routingError` types the queue receipt, null for values 32 to 35, which are
  ambiguous between firmware `ERRNO_*` enqueue results and `Routing.Error` refusals.
