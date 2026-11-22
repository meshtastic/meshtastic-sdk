- A connected node that renumbers on its first region set no longer leaves that write timing out.
  Firmware never acks it; its queue status and the `my_info` naming the new number complete it,
  and the session restarts on the same link with the new identity.
