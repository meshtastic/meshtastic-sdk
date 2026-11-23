- Admin setters map a `QueueStatus` rejection below 32, such as `DUTY_CYCLE_LIMIT` (9), through the
  `Routing.Error` table as RPCs already did, instead of `RateLimited`, and a duty-cycle NAK returns
  `Failed(DUTY_CYCLE_LIMIT)` instead of `NodeUnreachable`, which is left for a lost link.
