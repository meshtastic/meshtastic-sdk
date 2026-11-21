- `autoSyncTimeOnConnect` sends its `set_time_only` as soon as the Stage 1 `my_info` frame
  arrives, instead of an `AdminApi.setTime()` after `connect()` returns, so firmware can re-date
  packets it queued without a clock while they are still queued. On a fast TCP link firmware can
  flush them before it reads the sync.
