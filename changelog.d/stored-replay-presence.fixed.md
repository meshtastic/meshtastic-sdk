- The position and telemetry history firmware replays after the handshake no longer marks every
  node as heard now: a replayed packet without `rx_time` leaves `last_heard` alone, and no replay
  emits `CameOnline` or refreshes presence. A live packet without `rx_time` is still stamped with
  the engine clock.
