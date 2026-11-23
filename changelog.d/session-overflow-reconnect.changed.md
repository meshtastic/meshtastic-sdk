- With auto-reconnect on, a `sessionEvents` overflow while a collector runs ends the session and
  reconnects, counted against `AutoReconnectConfig.maxHandshakeRecoveries` (three in a row when it
  is `null`), and a collector that got the
  `RadioSessionOverflowException` collects `sessionEvents` again to resume: the new collection
  starts with `Ended` for the overflowed session, then the next `Ready`. A session that reached
  `Connected` resets the restart count when it ends, unless it ended in an overflow. With
  auto-reconnect off the stream stays failed, as before.
