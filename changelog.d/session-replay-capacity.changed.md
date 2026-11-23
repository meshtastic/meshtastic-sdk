- `Builder.sessionReceive()` defaults to 4096 events instead of 512, enough for the stored-history
  replay of a 250-node radio while the collector installs the `Ready` snapshot. The settle and
  handshake buffers hold 128 frames instead of 64, a full meshtasticd to-phone queue (100).
