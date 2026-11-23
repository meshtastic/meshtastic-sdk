- `ConnectionState.Failed(cause)` is a new case: the engine reports it, not `Disconnected` or
  `Reconnecting`, for a non-recoverable transport error, `FirmwareTooOld`, and an exhausted
  `maxAttempts` or `maxHandshakeRecoveries`, and stays there until `connect()` or `disconnect()`.
  An exhaustive `when` over `ConnectionState` needs the new branch.
- `AutoReconnectConfig.recoveryStep` and `ReconnectRecoveryStep` are removed before release; pass a
  `RadioTransportProvider` to `RadioClient.Builder.transport(identity, provider)` instead, which
  can also return a fresh transport per attempt.
- The Android `BleTransport(address | radio, resilience, refreshGattCacheOnConnect, builderAction)`
  factories are removed before release; call `BleTransport.refreshGattCacheOnNextConnect()` after a
  firmware update instead.
- With auto-reconnect on, a failed first attempt, a handshake timeout, the liveness watchdog, a
  handshake protocol error and `FromRadio.rebooted` are retried instead of ending the session, so
  `connect()` returns at the first `Connected` and throws only with the cause of `Failed`.
- With auto-reconnect off, a session drop no longer passes through `Reconnecting(cause, 1)` on its
  way to `Disconnected`.
