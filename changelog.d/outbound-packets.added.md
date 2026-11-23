- `RadioClient.outboundPackets` emits each `MeshPacket` the client queues for the radio, as written,
  with the id, `from`, session passkey and PKI fields the SDK filled in, so a host can log what it
  sent. It covers sends, paced sends, RPC requests and admin writes, never lockdown credentials.
