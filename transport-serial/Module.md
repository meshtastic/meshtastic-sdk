# Module transport-serial

USB/serial transport for the Meshtastic KMP SDK. Implements `RadioTransport` from
`:core` using [jSerialComm](https://fazecast.github.io/jSerialComm/) 2.11.x on both
JVM and Android. See [ADR-010](../docs/decisions/010-jserialcomm-jvm-serial.md) for the
library choice and [ADR-012](../docs/decisions/012-transport-threading.md) for the
blocking-IO threading contract jSerialComm requires.

## Supported targets and OS versions

| Target | Backend | Minimum OS |
|---|---|---|
| JVM — macOS | jSerialComm bundled `.dylib` (x86_64 + arm64) | macOS 10.15+ |
| JVM — Linux | jSerialComm bundled `.so` (x86_64, arm, arm64) | glibc 2.17+ (effectively any modern distro) |
| JVM — Windows | jSerialComm bundled `.dll` (x86_64, arm64) | Windows 10+ |
| Android | jSerialComm Android backend over `UsbDeviceConnection` | Android 8.0 (API 26 — repo `androidMinSdk`); requires USB Host hardware |
| iOS | — | Not supported (no general-purpose serial access without an MFi accessory) |

## Serial line configuration

Default baud rate is **115 200** (`JvmSerialPorts.DEFAULT_BAUD` / `AndroidSerialPorts.DEFAULT_BAUD`),
8 data bits, 1 stop bit, no parity, no flow control — the configuration the Meshtastic
firmware uses on its USB CDC/UART path. The factory `open(portName, baudRate)` accepts
non-default rates for unusual hardware, but the firmware itself ships at 115 200.

## USB device detection

The transport does not ship a fixed VID/PID allowlist; it enumerates whatever the OS
reports as a serial port and lets the caller pick. In practice Meshtastic-compatible
boards present one of the common USB-serial bridge chips:

| Bridge IC | Typical VID:PID | Notes |
|---|---|---|
| Silicon Labs CP210x (CP2102/2104) | `10C4:EA60` | Most ESP32-based boards (T-Beam, T-Echo, RAK) |
| WCH CH340/CH341 | `1A86:7523`, `1A86:55D4` | Some low-cost ESP32 dev kits |
| FTDI FT232 | `0403:6001`, `0403:6015` | Older / industrial boards |

`SerialPortInfo` exposes `usbVendorId` / `usbProductId` (both `Int?`) so hosts can build
their own picker UI and apply additional filters.

## Android wiring

On Android the host app is responsible for the `UsbManager.requestPermission(...)` flow;
`SerialPort.fromAndroidPort(...)` (used internally) opens an already-permissioned
`UsbDeviceConnection`. Required steps:

1. Call `AndroidSerialPorts.init(applicationContext)` **once** during app startup so
   jSerialComm can resolve the system `UsbManager`.
2. Declare `<uses-feature android:name="android.hardware.usb.host" />` and a
   `USB_DEVICE_ATTACHED` intent-filter in `AndroidManifest.xml`. The full manifest snippet
   and `device_filter.xml` example are in
   [`docs/integration-guide.md` §4 — Serial (USB)](../docs/integration-guide.md#serial-usb)
   and [`docs/architecture/android-platform-constraints.md`](../docs/architecture/android-platform-constraints.md).
3. Complete `UsbManager.requestPermission(device, pendingIntent)` before calling
   `AndroidSerialPorts.open(...)`.

Android 14+ also requires a foreground service of type `connectedDevice` to keep the USB
connection alive in the background — same constraint as BLE, see
[`android-platform-constraints.md`](../docs/architecture/android-platform-constraints.md).

## Key packages

- `org.meshtastic.sdk.transport.serial` — `JvmSerialPorts` (JVM) and `AndroidSerialPorts` (Android)
  factory objects exposing `list(): List<SerialPortInfo>` and
  `open(portName, baudRate = 115200): RadioTransport`. The underlying
  `JSerialCommTransport` is `internal` — callers always go through the factory.
- `org.meshtastic.sdk.transport.serial.SerialPortInfo` — discovery DTO with `name`, `description`,
  `usbVendorId`, `usbProductId`.
