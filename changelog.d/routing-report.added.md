- `MeshEvent.RoutingReport` reports each routing ACK or NAK addressed to us, for any packet id,
  including one sent in an earlier session or process: the node that sent it, the
  `Routing.Error`, whether the local radio produced it, the relay's node byte and the
  `ack_proof_status`. `isImplicitAckFor(destination)` tells an overheard relay from a delivery.
  It arrives on `events` and, after its packet, on `sessionEvents`.
