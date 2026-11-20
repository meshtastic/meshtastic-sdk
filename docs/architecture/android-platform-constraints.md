# Android platform constraints

This page covers the Android platform restrictions that affect an SDK integration, chiefly background connectivity and foreground services.

## Foreground service requirement (Android 14+)

Required on API 34 and later. Without a foreground service, BLE and USB-serial connections end when the app goes to the background.

### Background connectivity

The SDK does not start a foreground service for you. On Android 14 and later (API 34+), background execution limits stop:

- Bluetooth scans and connections
- USB Host API access
- long-lived socket reads and writes

Without a declared and started foreground service, the connection ends when your app enters the background.

### Solution

Run `connect()` and `disconnect()` from a `Service` that declares the foreground service permissions and type.

#### AndroidManifest.xml setup

```xml
<!-- Declare foreground service permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<application>
  <!-- Declare a foreground service with connectedDevice type -->
  <service
    android:name=".RadioConnectionService"
    android:foregroundServiceType="connectedDevice" />
</application>
```

#### Service implementation

The service must call `startForeground(...)` before `client.connect()`, and stop it in `onDestroy()` or on an explicit disconnect. Cancel the scope only after `disconnect()` returns, or the disconnect is cancelled with it:

```kotlin
class RadioConnectionService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var client: RadioClient

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground service BEFORE connecting
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Connected to mesh...")
        )

        // Now safe to connect
        scope.launch {
            client.connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch {
            try {
                client.disconnect()
            } finally {
                scope.cancel()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // … rest of service implementation
}
```

### References

- [Android Foreground Services (system documentation)](https://developer.android.com/develop/background-work/services/foreground-services)
- [Integration guide: Bluetooth setup](../integration-guide.md#bluetooth)

## BLE link recovery (opt-in)

Optional and off by default. `BleResilience` carries three Android-only mechanisms for nodes that fail to reconnect on a bare `BluetoothGatt` connect. The SDK still never prompts or manages lifecycle; the host decides whether to enable them.

- `bondFirst` calls `BluetoothDevice.createBond()` when the device is not bonded and polls the bond state for up to 30 s. Needs `BLUETOOTH_CONNECT` on API 31+; the OS shows its own pairing dialog.
- `autoConnectFallback` retries a direct connect that has not completed in 15 s with `autoConnect = true`, which Android resolves against a bonded device by address instead of waiting for a fresh advertisement.
- `refreshStaleGattCache` invokes the hidden `BluetoothGatt.refresh()` when discovery lacks the Meshtastic service. Android's hidden-API blocklist can refuse it; the connect then proceeds unchanged. It reads private fields of Kable 0.45 by reflection, so a Kable bump needs the field names re-checked in `transport-ble/consumer-rules.pro`, which the AAR ships to R8.

## Scoped storage (Android 11+)

This affects `SqlDelightStorageProvider`. With `targetSdk >= 30`, scoped storage blocks direct file I/O to `Environment.getExternalStorageDirectory()`, so point `baseDir` at `context.filesDir` (app-private) or `context.cacheDir`:

```kotlin
val storage = SqlDelightStorageProvider(baseDir = context.filesDir.absolutePath)
```

See [Android Scoped Storage documentation](https://developer.android.com/about/versions/12/behavior-changes-all#scoped-storage).

## USB permissions and device detection

Android's USB Host API needs the user's permission before an app opens a USB device. [Integration guide: Serial](../integration-guide.md#serial) covers the `UsbManager.requestPermission(...)` flow and the `AndroidSerialPorts` and `AndroidUsbSerialPorts` APIs.

## Related

- [ADR-002](../decisions/002-architecture.md): the engine architecture
- [ADR-012](../decisions/012-transport-threading.md): transport threading
- [Integration guide](../integration-guide.md)
