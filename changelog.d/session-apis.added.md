- `AdminApi.playRingtone()`, an acked write of `play_ringtone_request`.
- `RadioClient.requestNodeInfo(node, channel, user)` and `requestNodeInfoOnHeardChannel(node)`
  send our own `User` with the request and no `want_ack`, as the Android app does.
- `requestPosition(from, channel, position)` and `requestPositionOnHeardChannel(from, position)`
  share our position with the request and stamp `time` with the client clock.
- `RadioClient.requestPaxcount(node)` asks for a pax counter reading on the heard channel.
- `StoreForwardApi.requestLocalHistory(lastRequest, windowMinutes, maxMessages)` asks the
  connected node, as its own S&F server, to replay history.
- `RadioClient.remoteShell` (`RemoteShellApi`) sends PKI-encrypted `REMOTE_SHELL_APP` frames,
  decodes a node's inbound frames and mints session IDs.
- `RadioClient.mqttProxy` (`MqttProxyApi`) streams the device's MQTT proxy messages and
  delivers broker messages back to it.
- `RadioClient.sendPaced { … }`, the paced counterpart of the send DSL.
