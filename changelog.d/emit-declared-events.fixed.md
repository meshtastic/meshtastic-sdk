- `MeshEvent.QueueStatusChanged` fires for every queue status the device sends after the
  handshake, and `MeshEvent.MqttConnected` and `MeshEvent.MqttDisconnected` fire when a local
  `getDeviceConnectionStatus()` response shows the device's MQTT broker connection changed.
