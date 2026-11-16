- `RadioClient.deviceLogs`: the device's `log_record` frames as a `Flow<LogRecord>`, handshake
  included. Firmware sends them on serial and TCP when `security.debug_log_api_enabled` is set;
  the SDK does not change that setting. Drop-oldest at 64, no replay, so a slow collector never
  slows the engine and the logs never reach `events`.
