- A connected node that renumbers on its first region set no longer leaves that write timing out.
  The routing ACK from the new number completes it, whichever of the ACK and `my_info` arrives
  first, and the session restarts on the same link with the new identity.
