- Engine logs no longer carry the transport address or packet contents: the connect line names
  the transport kind and a 4-hex digest instead of the BLE MAC or TCP host, and the settle-buffer
  and node-flow drop warnings name only the frame variant and node number.
