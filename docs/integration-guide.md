# Integration guide

Recipes for embedding `meshtastic-sdk` in an application: dependencies, building a client, per-transport setup, storage, sending, admin, and testing. [api-reference.md](./api-reference.md) lists what exists and [observability.md](./observability.md) covers seeing what it does.

## Platform support

| Module | JVM | Android | iOS | JS, Wasm |
|---|---|---|---|---|
| `sdk-core`, `sdk-testing` | yes | yes | yes | yes |
| `sdk-transport-websocket` | yes | yes | yes | yes |
| `sdk-transport-tcp` | yes | yes | yes | no |
| `sdk-transport-ble` | yes | yes | yes | yes (Web Bluetooth) |
| `sdk-transport-serial` | yes | yes | no | no |
| `sdk-storage-sqldelight` | yes | yes | yes | no |

Every Android artifact sets `minSdk = 26` (Android 8.0). A web host runs the engine in the page, connects with `WebSocketTransport` or, in a browser with Web Bluetooth, `requestBleTransport()`, and stores state with `InMemoryStorageProvider` ([ADR-017](./decisions/017-web-targets.md)).

## 1. Add the dependency

```kotlin
// settings.gradle.kts: Maven Central is the only repository needed
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// build.gradle.kts
dependencies {
    implementation(platform("org.meshtastic:sdk-bom:<VERSION>"))
    implementation("org.meshtastic:sdk-core")
    implementation("org.meshtastic:sdk-storage-sqldelight")

    // The transports your app supports:
    implementation("org.meshtastic:sdk-transport-tcp")
    implementation("org.meshtastic:sdk-transport-ble")
    implementation("org.meshtastic:sdk-transport-serial")
    implementation("org.meshtastic:sdk-transport-websocket")

    testImplementation("org.meshtastic:sdk-testing")
}
```

Replace `<VERSION>` with a release from [CHANGELOG.md](../CHANGELOG.md). The BOM aligns every `sdk-*` artifact ([bom/README.md](../bom/README.md)).

## 2. Build a `RadioClient`

```kotlin
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.transport.tcp.TcpTransport

val client = RadioClient.Builder()
    .transport(TcpTransport(host = "meshtastic.local", port = 4403))
    .storage(SqlDelightStorageProvider(baseDir = "/var/data"))
    .build()

client.connect()   // returns at Ready; throws MeshtasticException once the client is Failed
```

`transport(...)` and `storage(...)` are required; everything else has a default ([api-reference.md, Construction](./api-reference.md#construction)). The engine reconnects after a recoverable drop by default, and a recoverable failure of the first connect is retried too, so `connect()` can stay suspended while the radio is unreachable. Bound it with `AutoReconnectConfig(maxAttempts = n)` or a `withTimeout`, or pass `.autoReconnect(AutoReconnectConfig.Disabled)` when the host owns reconnect policy.

## 3. Per-transport setup

### TCP

No platform setup. The node listens on TCP port 4403 when Wi-Fi or Ethernet is on; `meshtasticd` listens there too.

```kotlin
val transport = TcpTransport(host = "192.168.1.50", port = 4403)
```

Radios advertise `_meshtastic._tcp` over mDNS. `JvmTcpDiscovery()`, `AndroidTcpDiscovery(context)`, and `IosTcpDiscovery()` implement `TcpDiscovery.discover()`, a flow of `TcpDiscoveryEvent.Found(radio)` and `Lost(serviceName)`; `radio.toTransport()` opens a `TcpTransport`.

### Bluetooth

Scan with `BleScanner` and build the transport from the result; this works on every target.

```kotlin
import kotlinx.coroutines.flow.first
import org.meshtastic.sdk.transport.ble.BleScanner
import org.meshtastic.sdk.transport.ble.BleTransport

val radio = BleScanner.scan().first()
val transport = BleTransport(radio)
```

`BleScanner.scan()` filters on the Meshtastic service UUID; `DiscoveredRadio` carries `name`, `rssi`, and `address`. To pick a radio from a user-typed `ble:NEEDLE`, filter with `bleAdvertisementMatches`.

On Android, `BleTransport(address = "AA:BB:CC:DD:EE:FF")` finds the radio by a scan filtered on that address in each `connect()` and connects from its advertisement: a bonded radio gets one 5-second scan and then its bonded handle, an unbonded one three 5-second scans a second apart, then `connect()` fails with `MeshtasticException.Transport`. The scan needs `BLUETOOTH_SCAN`. `bondedBleDevices(context)` lists the host's bonded devices for a picker. `BleResilience(bondFirst = true, autoConnectFallback = true, refreshStaleGattCache = true)` turns on link recovery for difficult radios. `BleTransport.deviceLogs()` streams the radio's log ([observability.md](./observability.md#device-logs)).

On the JVM, `BleTransport(address = ...)` takes the `DiscoveredRadio.address` saved from a scan on the same host, or a MAC on Linux and Windows. Kable's identifier is BlueZ's device path wrapped in JSON on Linux (`{"object_path":"/org/bluez/hci0/dev_C2_5D_2D_C4_E5_6E"}`), the MAC on Windows and a CoreBluetooth UUID on macOS, which exposes no MAC. A valid identifier for the host is used directly; anything else makes `connect()` scan for up to 15 seconds and connect to the first radio whose identifier matches, exactly or by MAC, or fail with `MeshtasticException.Transport`.

After a firmware update, call `BleTransport.refreshGattCacheOnNextConnect()` on Android: the next connect that reaches the radio refreshes the GATT cache before PhoneAPI traffic begins. An update can require this refresh even when the cached table still includes every required characteristic. `BleResilience(refreshGattCacheAfterFailures = 6)` also refreshes once when reconnects keep failing, for a cache that went stale while the radio was away.

Platform requirements:

- **Android**: request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on API 31 and later; add `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN` if you don't derive location from scans. API 30 and earlier need `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION`. The SDK requests none of them.
- **iOS**: set `NSBluetoothAlwaysUsageDescription` in `Info.plist`, and add the `bluetooth-central` background mode for background use.
- **JVM**: Linux needs BlueZ. On macOS and Windows, Bluetooth through Kable is best-effort.

### Serial

JVM:

```kotlin
import org.meshtastic.sdk.transport.serial.JvmSerialPorts

val port = JvmSerialPorts.findMeshtastic().first()                 // List<SerialPortInfo>, filtered by USB vendor ID
val transport = JvmSerialPorts.open(port.name, baudRate = 115_200)
```

On Linux, `JvmSerialPorts.byIdPath(port.name)` returns the stable `/dev/serial/by-id/…` path, which survives replugs; `ttyACM` numbers don't.

Android, with jSerialComm:

```kotlin
import org.meshtastic.sdk.transport.serial.AndroidSerialPorts

AndroidSerialPorts.init(applicationContext)                 // once, for example in Application.onCreate()
// After UsbManager.requestPermission(...) is granted:
val transport = AndroidSerialPorts.open(portName, baudRate = 115_200)
```

Android, with usb-serial-for-android, the driver stack Meshtastic-Android uses. The SDK depends on it `compileOnly` because it's published on JitPack, so add the dependency and the repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement.repositories {
    maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
}
// build.gradle.kts
implementation("com.github.mik3y:usb-serial-for-android:3.12.0")
```

```kotlin
import org.meshtastic.sdk.transport.serial.AndroidUsbSerialPorts

val ports = AndroidUsbSerialPorts.list(context)              // devices a driver claims, permission or not
// After UsbManager.requestPermission(device, ...) is granted:
val transport = AndroidUsbSerialPorts.open(context, ports.first().name)
```

Every `open` has an overload with `onTextLine: (String) -> Unit`, which receives the text the device prints between frames.

The host owns the USB permission flow:

1. Declare `<uses-feature android:name="android.hardware.usb.host" />`, and optionally a receiver for `USB_DEVICE_ATTACHED` with a `res/xml/device_filter.xml` listing the USB vendor and product IDs to match.
2. Call `UsbManager.requestPermission(device, pendingIntent)` before `open`.
3. Open the port when the permission broadcast reports `EXTRA_PERMISSION_GRANTED`.

`samples/cli` has a complete JVM example. iOS has no serial transport; use TCP, Bluetooth, or WebSocket.

### WebSocket

For nodes that serve the WebSocket phone API at `/api/v1/ws`. Firmware support is in open pull requests; the module's [Module.md](../transport-websocket/Module.md) lists them.

```kotlin
import org.meshtastic.sdk.transport.websocket.WebSocketTransport

val transport = WebSocketTransport("ws://meshtastic.local")   // path defaults to /api/v1/ws
```

The node serves one client at a time; a newer client displaces the older one, which ends with `TransportState.Error(recoverable = false)`. A `wss://` node with a self-signed certificate needs an `HttpClient` that trusts it, passed as the second argument, or, in a browser, trust granted in the browser. Module.md has the close codes and browser constraints. On Android, declare the `INTERNET` permission.

### From a connection string

`TransportTarget.parse("tcp:192.168.1.50")` returns `TransportTarget.Tcp(host, port)`, and likewise for `ble:NEEDLE` and `serial:PORT[:BAUD]`, or `null` for anything else. Map the target to one of the transports in this section; the SDK leaves that step to the host so an app links only the transports it uses.

### Android background work

On API 34 and later, Bluetooth and USB connections stop in the background without a foreground service, and the SDK doesn't start one. Host the client in a `Service` with `android:foregroundServiceType="connectedDevice"`, declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` (and `POST_NOTIFICATIONS` for the notification), call `startForeground(...)` before `connect()`, and stop it after `disconnect()`. See the [Android foreground service documentation](https://developer.android.com/develop/background-work/services/foreground-services).

## 4. Storage

`SqlDelightStorageProvider(baseDir)` keeps one SQLite database per radio under `baseDir`, shared by every transport that reaches the same node. `baseDir = ""` selects in-memory mode, lost on close. `InMemoryStorageProvider()` from `sdk-core` keeps everything in RAM on every target, including the web.

On Android, set the context holder once before the first `connect()`:

```kotlin
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
    }
}
```

Then construct it as on other platforms, for example `SqlDelightStorageProvider(baseDir = filesDir.absolutePath)`.

## 5. Lifecycle and threading

- `connect()` suspends until `connection.value == ConnectionState.Connected` and throws `MeshtasticException` on failure ([error-taxonomy.md](./error-taxonomy.md)).
- `connect()` isn't idempotent: calling it while connected throws `AlreadyConnected`. Check `client.connection.value` first.
- `nodes`, `packets`, `events`, and `fromRadio` are hot flows that any number of collectors can share. `nodes` starts every subscription with a snapshot; the others deliver only what arrives while you collect. A slow collector loses items rather than slowing the engine ([api-reference.md, Streams](./api-reference.md#streams)).
- `disconnect()` is idempotent and never throws. A pending `MessageHandle.await()` returns `SendOutcome.Failure(SendFailure.Disconnected)`.
- `withConnection { … }` connects, runs a block, and always disconnects.

Build one `RadioClient` per radio and hold it at app scope, for example as a DI singleton, so a screen or configuration change doesn't tear the session down. [`samples/parity-android-app`](../samples/parity-android-app) is the compiled Android reference for building and connecting a client; it scopes its client to the activity to stay short.

## 6. Sending messages

```kotlin
val handle = client.sendText("hello mesh")
when (val outcome = handle.await()) {
    SendOutcome.Success -> println("acked, or a relay was heard")
    is SendOutcome.Failure -> println("failed: ${outcome.reason}")
}
```

For progress, collect `handle.state`: `Queued`, `Sent`, then `Acked`, `Delivered`, or `Failed(reason)`. [consumer-guides/error-handling.md](./consumer-guides/error-handling.md) covers which failures to retry.

## 7. Admin operations

```kotlin
// Read a config section
when (val result = client.admin.getConfig(AdminMessage.ConfigType.LORA_CONFIG)) {
    is AdminResult.Success -> println("LoRa region: ${result.value.lora?.region}")
    AdminResult.Timeout -> println("timed out")
    AdminResult.RateLimited -> println("rate limited; try again later")
    else -> println("failed: $result")
}

// Change one field: the builder reads the current section, so the others keep their values
client.admin.setLoraConfig {
    newBuilder().also { it.region = Config.LoRaConfig.RegionCode.US }.build()
}

// Write several sections in one transaction
val lora = client.configBundle.value?.configs?.firstNotNullOfOrNull { it.lora } ?: return
client.admin.editSettings {
    setConfig(Config.Builder().also { it.lora = lora }.build())
    setOwner(newOwner)
}

// Target another node
client.admin.forNode(NodeId(0x12345678)).reboot()
```

A config builder reads the section from the targeted device before calling its block, and what the block returns replaces the whole section, so change only the fields you mean to. [api-reference.md, AdminApi](./api-reference.md#adminapi) has every method; [error-taxonomy.md](./error-taxonomy.md#adminresult) explains each `AdminResult`.

## 8. Logging and diagnostics

The SDK is silent by default. Pass a `LogSink` to `Builder.logger(...)`; for frame-level debugging add `protocolLogging(...)`, which redacts payloads. [observability.md](./observability.md) also covers the raw `fromRadio` tap and device logs.

## 9. Testing your integration

```kotlin
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport

val transport = FakeRadioTransport(identity = TransportIdentity("fake:test"), autoHandshake = true)
val client = RadioClient.Builder()
    .transport(transport)
    .storage(InMemoryStorageProvider())
    .build()

client.connect()                        // the fake answers the handshake
transport.injectPacket(somePacket)      // drive inbound traffic
val sent = transport.outboundPackets()  // inspect what the client sent
```

`FakeRadioTransport` also injects admin, telemetry, traceroute, and routing replies. [testing/Module.md](../testing/Module.md) and `core/src/commonTest/` have working patterns.

## 10. PKI direct messages

The node encrypts and decrypts direct messages with X25519 keys; the SDK never handles a private key ([protocol.md §10](./protocol.md#10-pki-direct-messages-x25519--aes-ccm)).

- Inbound DM payloads arrive as `MeshPacket` with `decoded.public_key` populated whenever the firmware decrypted them with a known peer key. An empty `public_key` on a `decoded.portnum == TEXT_MESSAGE_APP` packet from a non-broadcast `to` field means the peer's identity could not be verified.
- `MeshEvent.KeyVerification` carries the steps of the device's numeric key verification (`KeyVerificationPrompt.EnterSecurityNumber`, `ShowSecurityNumber`, `ConfirmVerification`); start it and answer it with `AdminApi.keyVerification` (see `docs/protocol.md` §10).
- `MeshEvent.SecurityWarning.PublicKeyMismatch` reports a `NODEINFO_APP` dropped because its key differs from the stored one, once per node and offered key.
- `MeshEvent.SecurityWarning.DuplicatedPublicKey` and `MeshEvent.SecurityWarning.LowEntropyKey` flag device-reported key problems; treat the affected `NodeId` as untrusted for DMs until a manual re-verification round trips.

- Inbound direct messages arrive on `packets` already decoded.
- `sendDirectMessageEncrypted(to, text)` sets `pki_encrypted`, so the node encrypts to the recipient's key. `sendText` to a unicast destination also works when the node has the recipient's key.
- Each node's key is `NodeInfo.user.public_key`, and the local one is in `ownNode`. To guard against impersonation, compare a peer's current key with one the user verified before sending, since the engine doesn't refuse the send.
- `MeshEvent.SecurityWarning.DuplicatedPublicKey` means the node saw a key it already holds for another node; `LowEntropyKey` means the local key is weak.
- Firmware's key verification exchange runs over `KEY_VERIFICATION_APP`: start it with `admin.keyVerification(...)` and read replies with `asKeyVerification()`.

## 11. MQTT

When a node has MQTT on, packets that crossed MQTT arrive with `MeshPacket.via_mqtt = true` (also `MeshPacket.transport == PacketTransport.Mqtt`), and the node may publish what you send. Nothing on the client side needs configuring for that.

When `mqtt.proxy_to_client_enabled` is set, the node reaches the broker through the client instead: its MQTT traffic arrives as `MeshEvent.MqttProxyMessage`, the host publishes it, and broker traffic goes back through `sendRaw` as a `ToRadio` with `mqttClientProxyMessage` set. The SDK doesn't bundle an MQTT client ([roadmap.md](./roadmap.md) R-11). [protocol.md §14](./protocol.md#14-mqtt-proxy-mode) has the wire details.

## Where to next

- [API reference](./api-reference.md): every public symbol
- [Error taxonomy](./error-taxonomy.md): which failures throw, return, or arrive as events
- [Protocol notes](./protocol.md): the wire behavior the SDK speaks
- [Sample CLI](../samples/cli): a reference consumer for TCP, Bluetooth, and serial
- [ADR-005](./decisions/005-api-shape.md): why the API looks the way it does
