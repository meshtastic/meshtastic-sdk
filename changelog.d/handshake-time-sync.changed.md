- `autoSyncTimeOnConnect` sends its `set_time_only` as soon as the Stage 1 `my_info` frame
  arrives, instead of an `AdminApi.setTime()` after `connect()` returns. Firmware re-dates the
  packets it queued without a clock, and flushes them right after Stage 1.
