# Module transport-serial

USB serial transport for the Meshtastic KMP SDK. It implements `RadioTransport` from `:core` with [jSerialComm](https://fazecast.github.io/jSerialComm/) on the JVM and Android, and with usb-serial-for-android as a second Android backend. [ADR-010](../docs/decisions/010-jserialcomm-jvm-serial.md) covers the library choice, [ADR-016](../docs/decisions/016-usb-serial-for-android-backend.md) the usb-serial-for-android backend, and [ADR-012](../docs/decisions/012-transport-threading.md) the blocking-I/O threading contract.

## Supported targets

| Target | Backend | Requirements |
|---|---|---|
| JVM (macOS) | jSerialComm bundled `.dylib` (x86_64, arm64) | macOS 10.15 or later |
| JVM (Linux) | jSerialComm bundled `.so` (x86_64, arm, arm64) | glibc 2.17 or later |
| JVM (Windows) | jSerialComm bundled `.dll` (x86_64, arm64) | Windows 10 or later |
| Android | jSerialComm over `UsbDeviceConnection` (`AndroidSerialPorts`), or usb-serial-for-android (`AndroidUsbSerialPorts`, the host supplies the library) | API 26 (`androidMinSdk`) and USB host hardware |
| iOS | none | The iOS klibs carry only the common types (`SerialFrameAssembler`, `SerialPortInfo`, the vendor ID set). iOS has no general-purpose serial access without an MFi accessory. |

## Serial line configuration

The default is **115200** baud (`DEFAULT_BAUD` on `JvmSerialPorts`, `AndroidSerialPorts` and `AndroidUsbSerialPorts`), 8 data bits, 1 stop bit, no parity and no flow control, which is what the firmware uses on its USB CDC and UART path. `open(portName, baudRate)` accepts other rates for unusual hardware.

The reader stages at most the completed frames from one port read and hands them to the engine in order. If the frame channel fills, the reader waits before reading from the port again. The port and OS still determine how much incoming data can wait in their receive buffers.

## Finding a node

`list()` returns every port the OS reports. `MESHTASTIC_USB_VENDOR_IDS` holds the USB vendor IDs Meshtastic nodes enumerate with: the nRF52 and RP2040 native USB stacks, Espressif native USB, Seeed boards, and the CP210x, CH34x and FTDI bridge chips most ESP32 boards carry. `SerialPortInfo.isLikelyMeshtastic()` tests a port against that set, and `JvmSerialPorts.findMeshtastic()` filters `list()` with it. The bridge chips are generic, so a match means the port is plausibly a Meshtastic node, not that it is one.

`SerialPortInfo` carries `name`, `description`, `transport` (`Usb`, `Native`, `Bluetooth` or `Unknown`), `usbVendorId`, `usbProductId`, `usbManufacturer`, `usbProduct` and `usbSerialNumber`, so a host can build its own picker. On Linux, `JvmSerialPorts.byIdPath(portName)` returns the stable `/dev/serial/by-id/...` path, which survives replugs and reboots where `ttyACM*` numbers don't.

## Text between frames

Firmware prints its serial debug log as text between protobuf frames. `open(portName, baudRate, onTextLine)` on `JvmSerialPorts` and `AndroidSerialPorts`, and `open(context, device, baudRate, logger, onTextLine)` on `AndroidUsbSerialPorts`, pass each such line to `onTextLine`. The callback runs on the reader thread, so it must not block; an exception it throws is swallowed.

## Android wiring

The host app owns the `UsbManager.requestPermission(...)` flow. For the jSerialComm backend:

1. Call `AndroidSerialPorts.init(applicationContext)` once at startup so jSerialComm can reach the system `UsbManager`.
2. Declare `<uses-feature android:name="android.hardware.usb.host" />` and a `USB_DEVICE_ATTACHED` intent filter in `AndroidManifest.xml`. [`docs/integration-guide.md` → Serial](../docs/integration-guide.md#serial) covers the manifest and `device_filter.xml`.
3. Find candidates with `AndroidSerialPorts.usbDevices(context)`, which ignores permission, and complete `UsbManager.requestPermission(device, pendingIntent)`. `AndroidSerialPorts.list()` shows only permitted ports.
4. Call `AndroidSerialPorts.open(...)`.

Android 14 and later need a foreground service of type `connectedDevice` to keep the USB connection alive in the background, the same constraint as Bluetooth; see [`android-platform-constraints.md`](../docs/architecture/android-platform-constraints.md).

## Key packages

- `org.meshtastic.sdk.transport.serial`: the `JvmSerialPorts` (JVM) and `AndroidSerialPorts` (Android) factory objects, with `list(): List<SerialPortInfo>` and `open(portName, baudRate = 115200): RadioTransport`. The underlying `JSerialCommTransport` is internal.
- `AndroidUsbSerialPorts`: the usb-serial-for-android backend, with `list(context)` and `open(context, device | deviceName, baudRate = 115200)`. The library is `compileOnly` here and published only on JitPack, so the host adds `com.github.mik3y:usb-serial-for-android` and the JitPack repository. Its probe table adds the RAK4631, T-Beam v1.1 and nRF52840 CDC entries Meshtastic-Android uses, and it opens the port with DTR and RTS asserted, which native USB CDC boards need before they answer.
- `SerialFrameAssembler`: the public framing state machine (START1, START2, length, resync on garbage) both backends feed, for hosts that bring their own byte stream. Its `onTextLine` receives the text between frames.
- `SerialPortInfo`, `SerialPortTransport`, `MESHTASTIC_USB_VENDOR_IDS` and `isLikelyMeshtastic()`: port discovery.
