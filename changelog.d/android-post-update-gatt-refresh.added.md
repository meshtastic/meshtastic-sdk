- The Android BLE transport can attempt a GATT cache refresh before the PhoneAPI handshake when
  the app requests it after a firmware update.
- Shutting down a BLE transport created by an SDK factory releases the factory's Kable peripheral.
