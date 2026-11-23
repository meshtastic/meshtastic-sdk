- The Android BLE transport refreshes its GATT cache before the PhoneAPI handshake of the next
  connect that reaches the radio after `BleTransport.refreshGattCacheOnNextConnect()`, for use after
  a firmware update.
- Shutting down a BLE transport created by an SDK factory releases the factory's Kable peripheral.
