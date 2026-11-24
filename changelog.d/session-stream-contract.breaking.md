- `RadioClient.sessionEvents` is now a non-null `Flow<RadioSessionEvent>`; collecting it without
  `Builder.sessionReceive()` fails with `IllegalStateException`. `RadioSessionEvent.Ended` now also
  closes a session that stopped at `Lockdown`. `deviceLogs`, `outboundPackets` and `displayFrames`
  emit `SessionScoped<T>`, which carries the `sessionId` of the session each item belongs to.
