- `RadioClient.Builder.sessionReceive()` enables an ordered, bounded stream of handshake
  snapshots and session-tagged packets for one app consumer. If the SDK queue overflows, the
  stream drains accepted events and reports the affected session while the radio stays online.
  On reconnect, the SDK starts a reader for the new transport connection and discards work
  queued by the previous reader.
- Hosts can opt into ordered post-handshake envelopes such as queue status and check whether a
  raw command was admitted to the transport send queue.
