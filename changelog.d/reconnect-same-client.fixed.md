- A `RadioClient` can `connect()` again after `disconnect()`, or after a failed or cancelled
  `connect()`. The second call used to throw "Channel was closed".
