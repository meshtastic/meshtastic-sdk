# Integration guide

End-to-end recipes for embedding `meshtastic-sdk` in an application.
Pairs with [`api-reference.md`](./api-reference.md) (what exists) and
[`observability.md`](./observability.md) (how to see what it's doing).

## Android setup checklist

If you target Android, work through this checklist once and you can skip
the platform-specific call-outs scattered through later sections.

> **Minimum API: Android 8.0 Oreo (API 26).** All `sdk-*`
> Android artifacts pin `minSdk = 26`. An app with `minSdk < 26` will
> fail to resolve the dependency at build time. There is no runtime
> fallback for older OS versions.

### 1. AndroidManifest.xml — paste this block

The block below covers every transport. Trim the permissions you do
not use.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <!-- BLE transport (API 31+; for API ≤ 30, also add BLUETOOTH,
         BLUETOOTH_ADMIN, and ACCESS_FINE_LOCATION) -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                     android:usesPermissionFlags="neverForLocation"
                     tools:targetApi="31" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"
                     tools:targetApi="31" />

    <!-- Foreground service (required on API 34+ to keep BLE/USB
         connections alive in the background). -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- USB-serial transport -->
    <uses-feature android:name="android.hardware.usb.host" />

    <application>

        <!-- Foreground service hosting your RadioClient -->
        <service
            android:name=".RadioConnectionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <!-- USB attach/detach intents (optional but recommended) -->
        <receiver
            android:name=".UsbDeviceReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
                <action android:name="android.hardware.usb.action.USB_DEVICE_DETACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </receiver>

    </application>
</manifest>
```

### 2. res/xml/device_filter.xml (USB only)

```xml
<resources>
    <usb-device vendor-id="0x10c4" product-id="0xea60" />  <!-- CP2102 (Silicon Labs) -->
    <usb-device vendor-id="0x1a86" product-id="0x7523" />  <!-- CH340 -->
    <usb-device vendor-id="0x0403" product-id="0x6001" />  <!-- FTDI -->
</resources>
```

### 3. Runtime work in code

| Concern | Where to look |
|---|---|
| Requesting BLE runtime permissions | [§4 BLE](#ble) |
| Starting a foreground service before `connect()` | [§4 BLE](#ble) (Android 14+ requirement) |
| `UsbManager.requestPermission(...)` flow | [§4 Serial (USB)](#serial-usb) |
| Wiring `AndroidContextHolder` for SqlDelight storage | [§5 Storage on Android](#5-storage-on-android) |
| Lifecycle-safe `Flow` collection in Fragments / Compose | [Reactive lifecycle management guide](consumer-guides/reactive-lifecycle-management.md) |
| Deciding what to retry vs surface to the user | [Error-handling guide](consumer-guides/error-handling.md) |

## 1. Add the dependency

```kotlin
// settings.gradle.kts — Maven Central is the only repository needed
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// build.gradle.kts (consumer module)
dependencies {
    implementation(platform("org.meshtastic:sdk-bom:<version>"))
    implementation("org.meshtastic:sdk-core")
    implementation("org.meshtastic:sdk-storage-sqldelight")

    // Pick the transports your app supports:
    implementation("org.meshtastic:sdk-transport-tcp")
    implementation("org.meshtastic:sdk-transport-ble")
    implementation("org.meshtastic:sdk-transport-serial")  // JVM + Android
}
```

The BOM aligns every `sdk-*` artifact to one version (see
[`bom/README.md`](../bom/README.md) for full details).
`sdk-testing` is available as `testImplementation`.

## 2. Build a `RadioClient`


```kotlin
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.transport.tcp.TcpTransport

val client = RadioClient.Builder()
    .transport(TcpTransport(host = "meshtastic.local", port = 4403))
    .storage(SqlDelightStorageProvider(baseDir = "/var/data"))   // "" = in-memory
    .build()

client.connect()        // throws MeshtasticException on failure
```

Required Builder calls: `transport(...)`, `storage(...)`. Everything
else has sensible defaults — see [`api-reference.md` §Construction](./api-reference.md#construction).

## 4. Per-transport setup

### TCP

Zero platform setup. Works on JVM, Android, iOS, and Linux.

```kotlin
val transport = TcpTransport(host = "192.168.1.50", port = 4403)
```

The Meshtastic firmware listens on TCP/4403 when the WiFi/Ethernet
interface is enabled.

### BLE

```kotlin
import com.juul.kable.Peripheral
import com.juul.kable.Scanner

val ad = Scanner {
    filters { match { services = listOf(/* Meshtastic service UUID */) } }
}.advertisements.first()

val transport = BleTransport(
    peripheral = Peripheral(ad),
    address    = ad.identifier.toString(),   // storage identity only
)
```

Platform requirements:

- **Android** — declare and request the runtime permissions appropriate
  to your `targetSdk`:
  - SDK ≥ 31: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`. Add
    `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN`
    if you do not need location-derived scan results.
  - SDK ≤ 30: `BLUETOOTH`, `BLUETOOTH_ADMIN`, plus
    `ACCESS_FINE_LOCATION` for scanning.
  Bonding is handled by the OS once the SDK initiates GATT.

  **⚠️ Android 14+ Foreground Service requirement:** On API 34+, BLE and USB connections die in the background without a foreground service. The SDK does **not** start one for you. Wrap your `connect()`/`disconnect()` calls in an `Activity`/`Service` that declares foreground service permissions and type:

  ```xml
  <!-- AndroidManifest.xml -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

  <application>
    <service
      android:name=".RadioConnectionService"
      android:foregroundServiceType="connectedDevice" />
  </application>
  ```

  Your `Service` must call `startForeground(...)` with a notification before `connect()` and stop it in `onDestroy()` or when disconnecting. See [Android system documentation](https://developer.android.com/develop/background-work/services/foreground-services) for details.

- **iOS** — set `NSBluetoothAlwaysUsageDescription` in `Info.plist` and
  request `CBCentralManager` authorization before scanning. Coordinate
  background usage via `bluetooth-central` background mode if needed.
- **JVM (desktop)** — Linux requires BlueZ; macOS/Windows BLE through
  Kable is best-effort.

### Serial (USB)

JVM:

```kotlin
import org.meshtastic.sdk.transport.serial.JvmSerialPorts

val ports = JvmSerialPorts.list()                         // List<String>
val transport = JvmSerialPorts.open(ports.first(), baudRate = 115200)
```

Android:

```kotlin
import org.meshtastic.sdk.transport.serial.AndroidSerialPorts

// Application.onCreate() or DI bootstrap:
AndroidSerialPorts.init(applicationContext)

// After UsbManager.requestPermission(...) succeeds:
val transport = AndroidSerialPorts.open(portName, baudRate = 115200)
```

#### AndroidManifest.xml setup

```xml
<!-- Declare USB host hardware feature -->
<uses-feature android:name="android.hardware.usb.host" />

<!-- Declare USB device attachment broadcast receiver (optional but recommended) -->
<application>
  <receiver android:name=".UsbDeviceReceiver"
            android:exported="true">
    <intent-filter>
      <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
      <action android:name="android.hardware.usb.action.USB_DEVICE_DETACHED" />
    </intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
               android:resource="@xml/device_filter" />
  </receiver>
</application>
```

#### Device detection and permission flow

You are responsible for:

1. **Filtering devices** — match Meshtastic device VID/PID in a `res/xml/device_filter.xml`:

   ```xml
   <!-- res/xml/device_filter.xml -->
   <resources>
     <usb-device vendor-id="0x10c4" product-id="0xea60" />  <!-- CP2102 (Silicon Labs) -->
     <usb-device vendor-id="0x1a86" product-id="0x7523" />  <!-- CH340 -->
     <usb-device vendor-id="0x0403" product-id="0x6001" />  <!-- FTDI -->
   </resources>
   ```

2. **Requesting permission** — before calling `AndroidSerialPorts.open(...)`:

   ```kotlin
   val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
   usbManager.requestPermission(device, PendingIntent.getBroadcast(
       context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
   ))
   ```

3. **Handling the broadcast** — in your `BroadcastReceiver`:

   ```kotlin
   class UsbDeviceReceiver : BroadcastReceiver() {
       override fun onReceive(context: Context, intent: Intent) {
           if (intent.action == ACTION_USB_PERMISSION) {
               val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
               if (granted) {
                   // User approved; now open the port
                   val transport = AndroidSerialPorts.open(portName)
               }
           }
       }
   }
   ```

See `samples/cli` for a complete worked example.

iOS is **not** supported — use TCP or BLE.

## 3. Storage on Android

`SqlDelightStorageProvider` needs an Android `Context` for its
`AndroidSqliteDriver`. There is **no** `init(context)` method — set the
holder once before the first `activate(...)`:

```kotlin
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
    }
}
```

Then construct as on any other platform:

```kotlin
val storage = SqlDelightStorageProvider(baseDir = filesDir.absolutePath)
```

`baseDir = ""` selects in-memory mode (handy for tests; lost on close).
Otherwise the provider creates one SQLite file per `TransportIdentity`
under `$baseDir/`.

## 4. Lifecycle, threading, and shutdown

- `connect()` suspends until `connection.value == ConnectionState.Connected`
  and throws `MeshtasticException` on any failure (see
  [`error-taxonomy.md`](./error-taxonomy.md)).
- A second call to `connect()` while already connected throws
  `MeshtasticException.AlreadyConnected` — this is **not** idempotent
  by design. Guard at your call site (`if (client.connection.value !is Connected)`).
- All public flows (`nodes`, `packets`, `events`) are cold/Multi-collectorable;
  collect from anywhere. Backpressure is `SUSPEND` for `nodes`/`packets`
  and `DROP_OLDEST` for `events` (use `events` for soft observability,
  not for accounting).
- `disconnect()` is idempotent and never throws. Pending
  `MessageHandle.await()` resolves to `SendOutcome.Failure(Disconnected)`.
- The `RadioClient` owns a `SupervisorJob`. To run multiple radios in
  parallel, build separate `RadioClient` instances.

## 5. Sending messages

```kotlin
val handle = client.sendText("hello mesh")
when (val outcome = handle.await()) {
    SendOutcome.Success    -> println("acked / rebroadcast heard")
    is SendOutcome.Failure -> println("failed: ${outcome.reason}")
}
```

Want progress instead of the terminal outcome? Collect `handle.state` —
it transitions `Queued → Sent → Acked|Delivered|Failed(reason)`. See
[`api-reference.md` §SendState](./api-reference.md#sendstate-sendfailure-sendoutcome).

## 6. Logging and diagnostics

By default the SDK is silent. Wire a `LogSink` at build time and, for
deep debugging, opt into `protocolLogging` (with redaction). Full
guide: [`observability.md`](./observability.md).

## 7. Testing your integration

```kotlin
// testImplementation("org.meshtastic:sdk-testing")
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider

val transport = FakeRadioTransport()
val client = RadioClient.Builder()
    .transport(transport)
    .storage(InMemoryStorageProvider())
    .build()

// Drive the engine by feeding `FromRadio` envelopes through the fake transport.
// See testing/Module.md and core/src/commonTest/ for working patterns.
```

## 8. PKI direct messages (DMs)

Direct messages between two nodes are encrypted end-to-end with X25519 + AES-CTR keys derived from each peer's `User.public_key`. The SDK does not perform the crypto itself — the firmware does — but it does surface everything you need to verify peers:

- Inbound DM payloads arrive as `MeshPacket` with `decoded.public_key` populated whenever the firmware decrypted them with a known peer key. An empty `public_key` on a `decoded.portnum == TEXT_MESSAGE_APP` packet from a non-broadcast `to` field means the peer's identity could not be verified.
- The first time a peer's `public_key` is observed (or when it changes), the engine emits `MeshEvent.KeyVerification` with a `KeyVerificationPrompt` describing the new fingerprint. Consumers should surface this to the user (TOFU/SAS-style) and persist the accepted key alongside the `NodeId`.
- `MeshEvent.SecurityWarning.DuplicatedPublicKey` and `MeshEvent.SecurityWarning.LowEntropyKey` flag `node_info` records whose `public_key` is suspect; treat the affected `NodeId` as untrusted for DMs until a manual re-verification round trips.

Sending DMs is the same as any other text message — call `client.sendText(...)` with a non-broadcast `to` — but you should refuse the call if the destination's last-seen `public_key` does not match what the user previously verified. The engine will not reject the send for you.

## 9. MQTT proxy mode (transparent)

Some devices participate in a regional MQTT mesh. When the firmware has MQTT enabled, inbound packets that originated over MQTT arrive with `MeshPacket.via_mqtt = true` and outbound packets you send may be re-broadcast to the MQTT topic by the device itself. Both sides are transparent to the SDK: there is no `transport-mqtt` artifact (R-11 in [`roadmap.md`](./roadmap.md)) and you do not need to configure anything beyond the device. See [`protocol.md` §14](./protocol.md#14-mqtt-proxy-mode) for the wire details and the topic naming convention; `via_mqtt` is the only signal the SDK exposes today.

## Where to next

- [API reference](./api-reference.md) — every public symbol
- [Error taxonomy](./error-taxonomy.md) — which failures are throws vs. results vs. events
- [Protocol notes](./protocol.md) — wire-level behavior the SDK speaks
- [Sample CLI](../samples/cli) — a complete reference consumer for TCP, BLE, and serial
- [ADR-005](./decisions/005-api-shape.md) — why the surface looks the way it does
