- `RadioClient.sendText` takes `wantAck` (default `true`), as `sendDirectMessage` does. With it
  off the packet carries the device hop limit. The previous signature stays, hidden, so compiled
  callers keep linking.
