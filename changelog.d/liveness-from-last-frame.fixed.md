- The liveness watchdog fires between 60 and 65 s after the last decoded `FromRadio`. It
  checked every 30 s, so a link that went quiet just before a check timed out after about 31 s.
  During a lockdown wait it is armed from the lockdown status, so a socket that goes half-open
  before any heartbeat reply still fails the `connect()`.
