- A config, module config or channel read answered by the connected node updates `configBundle`
  or `channels`, in memory and in storage, and emits `ConfigChanged` or `ChannelsChanged` when it
  changes them. A value the firmware adjusted on a write no longer stays as written until the next
  handshake, and `listChannels()` now refreshes `channels`.
