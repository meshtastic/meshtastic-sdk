- `RemoteShellApi.send` queues each frame behind the paced radio queue, as `sendPaced` does. A
  typed burst sent unpaced overflowed NimBLE firmware's three-write inbound queue, which drops the
  rest.
