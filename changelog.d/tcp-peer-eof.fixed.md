- Report a clean TCP peer close promptly. A transport read or write failure, including that EOF
  and a stalled write past `sendTimeout`, now reconnects when auto-reconnect is enabled instead
  of ending the session.
