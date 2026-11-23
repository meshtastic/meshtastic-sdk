- `connect()` holds its admission until its run's scope, config-save channel and coroutines are
  installed. A `disconnect()` from another thread in that window no longer lets a second
  `connect()` start a run that the first then overwrote with its cancelled scope; the second
  `connect()` gets `AlreadyConnected` until the first has returned.
