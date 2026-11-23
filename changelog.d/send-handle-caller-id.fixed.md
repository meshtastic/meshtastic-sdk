- `send(packet)` with a nonzero `packet.id` returns a `MessageHandle` whose `id` is that packet id,
  as `sendPaced` already did. Before, the handle carried an unrelated SDK id.
