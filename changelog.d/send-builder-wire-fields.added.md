- `SendBuilder` sets the fields a durable outbox needs: `id(MessageId)` sends under a caller-chosen
  packet id, `replyTo(MessageId)` sets `Data.reply_id`, `reaction()` sets `Data.emoji`, and
  `pkiEncrypted()` sends a PKI direct message on channel 0 with the recipient key from the node map
  (`pkiEncrypted(publicKey)` pins one). `RadioClient.nextPacketId()` draws an id before the send.
