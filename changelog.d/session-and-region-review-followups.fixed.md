- A passkey buffered during the handshake no longer replaces a newer passkey for the
  same node, so `adminSessions` keeps the latest observation by receipt time.
