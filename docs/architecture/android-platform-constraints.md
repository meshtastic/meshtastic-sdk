# Android platform constraints

A guide to Android-specific platform restrictions that affect Meshtastic SDK integration, particularly in the context of background connectivity and foreground services.

## Foreground Service requirement (Android 14+)

**Status:** Required on API 34+
**Impact:** BLE and USB-serial connections die in the background without a foreground service.

### Background connectivity issue

The Meshtastic SDK does **not** start a foreground service for you. On Android 14+ (API 34+), the OS enforces strict background execution limits that prevent:

- BLE (Bluetooth Low Energy) scans and connections
- USB Host API access
- Persistent socket reads/writes

**If you do not declare and start a foreground service, the connection will terminate when your app enters the background.**

### Solution

Wrap your `connect()` / `disconnect()` calls in an `Activity` or `Service` that declares the appropriate foreground service permissions and type.

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

Your `Service` must call `startForeground(...)` **before** calling `client.connect()` and stop it in `onDestroy()` or when explicitly disconnecting:

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
            client.disconnect()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    // … rest of service implementation
}
```

### References

- [Android Foreground Services (system documentation)](https://developer.android.com/develop/background-work/services/foreground-services)
- [Integration guide — BLE platform setup](../integration-guide.md#ble)

## BLE link recovery (opt-in)

**Status:** Optional, off by default

`BleResilience` carries three Android-only mechanisms for radios that fail to reconnect on a bare `BluetoothGatt` connect. The SDK still never prompts or manages lifecycle; the host decides to enable them.

- `bondFirst` calls `BluetoothDevice.createBond()` when the device is not bonded and polls the bond state for up to 30 s. Needs `BLUETOOTH_CONNECT` on API 31+; the OS shows its own pairing dialog.
- `autoConnectFallback` retries a direct connect that has not completed in 15 s with `autoConnect = true`, which Android resolves against a bonded device by address instead of waiting for a fresh advertisement.
- `refreshStaleGattCache` invokes the hidden `BluetoothGatt.refresh()` when discovery lacks the Meshtastic service. Android's hidden-API blocklist can refuse it; the connect then proceeds unchanged. It reads private fields of Kable 0.45 by reflection, so a Kable bump needs the field names re-checked and a release build needs the `consumer-rules.pro` keep rules.

## Scoped storage (Android 11+)

**Status:** Optional concern for `SqlDelightStorageProvider`

If your app targets `targetSdk >= 30`, scoped storage rules apply:

- Direct file I/O to `Environment.getExternalStorageDirectory()` is blocked.
- Prefer `context.filesDir` (app-private) or `context.cacheDir` for `SqlDelightStorageProvider.baseDir`.

Example:

```kotlin
val storage = SqlDelightStorageProvider(baseDir = context.filesDir.absolutePath)
```

See [Android Scoped Storage documentation](https://developer.android.com/about/versions/12/behavior-changes-all#scoped-storage).

## USB permissions and device detection

**Status:** Required for USB-serial integration

Android's USB Host API requires explicit user permission before accessing USB devices. See [integration guide — Serial (USB) setup](../integration-guide.md#serial-usb) for the full `UsbManager.requestPermission(...)` flow and `AndroidSerialPorts` API.

---

**Related:** [ADR-002 (threading)](../decisions/002-architecture.md), [ADR-012 (transport threading)](../decisions/012-transport-threading.md), [integration guide](../integration-guide.md)
