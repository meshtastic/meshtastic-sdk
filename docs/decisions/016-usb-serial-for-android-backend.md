# ADR-016: usb-serial-for-android as a second Android serial backend

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-06 |
| **Deciders** | Maintainers |
| **Supersedes** | — |
| **Related** | [ADR-010](010-jserialcomm-jvm-serial.md), [ADR-012](012-transport-threading.md), [`docs/architecture/module-graph.md`](../architecture/module-graph.md) |

## Context

ADR-010 chose jSerialComm for both JVM and Android so one library covers every target.
Meshtastic-Android, and the hosts that embed its code, run USB serial on
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) instead: it is the
driver stack the app has shipped and debugged for years, its probe table is where the app
records the native USB-CDC boards (RAK4631, T-Beam v1.1, nRF52840 CDC) that need extra
entries, and a host that already carries it does not want a second native-serial library in
the APK.

The library is published on JitPack, not Maven Central. A hard `implementation` dependency
from a Maven Central artifact would make every `:transport-serial` consumer add the JitPack
repository, including JVM hosts and Android hosts on jSerialComm.

## Decision

`:transport-serial` ships `AndroidUsbSerialPorts`, an Android-only factory backed by
usb-serial-for-android, beside `AndroidSerialPorts`. The library is a `compileOnly` dependency:
a host that calls `AndroidUsbSerialPorts` adds `com.github.mik3y:usb-serial-for-android` and
the JitPack repository itself; every other consumer is untouched.

The transport asserts DTR and RTS after `setParameters`, as the app does, because native
USB-CDC firmware waits for them before answering the wake bytes. It reuses the public
`SerialFrameAssembler` and the frame-channel publisher the jSerialComm backend uses, and its
data plane takes an already-open `UsbSerialPort` so the host test drives it with a fake port.

## Rationale

- Parity with the app: the same driver, probe table and control-line handling the app has
  proven on real boards.
- No cost to hosts that do not use it: `compileOnly` keeps the JitPack repository and the
  AAR out of their builds.
- One artifact, one `TransportIdentity` scheme (`serial-android:<deviceName>`), so storage keyed
  by identity is the same whichever backend opened the device.

## Alternatives considered

| Option | Why not |
|---|---|
| `implementation` dependency | Every consumer would need JitPack configured, including JVM hosts. |
| A separate `:transport-serial-usb` module | A second artifact for one Android object; the module boundary adds nothing the `compileOnly` scope does not. |
| Replace jSerialComm on Android | ADR-010's single-library posture still serves hosts without usb-serial-for-android. |

## Consequences

### Positive
- Hosts built on Meshtastic-Android's USB code adopt the SDK without changing driver stacks.

### Negative / costs
- Two Android serial backends to keep in step on wake bytes, control lines and read loops.
- `AndroidUsbSerialPorts` cannot be exercised in CI; `UsbSerialConformanceTest`
  (androidDeviceTest) and scenario A3b in `docs/manual-tests.md` cover it on a board.
