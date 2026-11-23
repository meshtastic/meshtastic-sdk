- `FakeRadioTransport` with `autoHandshake` answers only the first `get_owner_request` of each
  `connect()`, the engine's passkey seed, and sets the reply's `request_id` to the request's packet
  id as firmware does. Later `getOwner()` calls are left for the test to answer.
