- A frame that arrives between the inter-stage heartbeat and the Stage 2 request is no longer
  lost. A reconnecting radio delivers its queued phone packets there, so a text message received
  while the link was down now reaches `RadioClient.packets` and `RadioClient.sessionEvents` once
  the session is `Ready`.
