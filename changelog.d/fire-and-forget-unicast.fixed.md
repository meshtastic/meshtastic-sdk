- A unicast send without `want_ack` or `want_response` completes as soon as it is dispatched, as a broadcast one
  already did; its `MessageHandle` no longer stays in `Sent` until disconnect.
