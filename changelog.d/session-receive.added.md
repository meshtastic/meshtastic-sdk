- `RadioClient.Builder.sessionReceive()` enables an ordered, bounded stream of handshake
  snapshots and session-tagged packets for one app consumer. If the SDK queue overflows, the
  stream drains accepted events and reports the affected session while the radio stays online.
  On reconnect, the SDK starts a reader for the new transport connection and discards work
  queued by the previous reader.
- After `Ready` the stream carries typed `NodeChanged`, `ConfigChanged`, `ChannelsChanged` and
  `Event` in order with packets, so an app can project the session into its own store without
  decoding envelopes. Acknowledged local admin writes appear there once the call returns.
- Hosts can check whether a raw command was admitted to the transport send queue.
