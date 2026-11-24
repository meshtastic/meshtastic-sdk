- A stalled lockdown credential write is bounded by the fixed 30 s transport write timeout instead
  of the ACK timeout, so a long ACK wait no longer delays detecting a dead link.
