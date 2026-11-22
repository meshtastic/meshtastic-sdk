- Acked admin writes, including those inside `editSettings` and `batch`, go through the paced send
  queue, one in flight until its QueueStatus, so a burst no longer overruns the device's
  three-packet BLE inbound queue.
