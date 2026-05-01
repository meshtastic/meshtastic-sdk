# ADR-010: jSerialComm for the JVM serial transport

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-04-19 |
| **Deciders** | Maintainers |
| **Supersedes** | — |
| **Related** | [ADR-002](002-architecture.md), [ADR-006](006-multi-module-rationale.md), [`docs/architecture/module-graph.md`](../architecture/module-graph.md) |

## Context

`:transport-serial` is multiplatform: it must work on Android (USB-OTG
serial) and on the JVM (macOS, Linux, Windows) without per-platform user
setup. We initially considered pairing `usb-serial-for-android` on
Android with one of jSerialComm / RXTX / PureJavaComm / JSerial on the
JVM, but settled on a **single library across both targets** —
jSerialComm 2.x ships native libs for win/mac/linux **and** android-ndk
in one jar, and on Android opens an already-permissioned
`UsbDeviceConnection` (via `SerialPort.fromAndroidPort`) rather than
touching `/dev/tty`, which would require root.

Forces in tension:
- **Cross-platform consistency** — must Just Work on all three desktop OSes.
- **License compatibility** — we are GPL-3.0; Apache-2.0 / EPL / LGPL deps
  are fine, copyleft conflicts are not (see [ADR-004](004-licensing.md)).
- **Maintenance cadence** — picking an abandoned library would push us into
  forking territory.
- **Bundled native binaries** — the library must ship its own `.so/.dylib/.dll`
  so consumers don't have to install drivers manually.

## Decision

The JVM source set of `:transport-serial` uses
**`com.fazecast:jSerialComm`** — and so does the Android source set, via
`SerialPort.fromAndroidPort(usbDeviceConnection)`. There is no separate
`usb-serial-for-android` dependency. The Android caller still owns the
`UsbManager` permission Intent and hands the resulting
`UsbDeviceConnection` to `AndroidSerialPorts.openFromDeviceConnection`.
On JVM, `JvmSerialPorts` calls `SerialPort.getCommPort(name)` against
the names returned by `sp_list_ports`. The shared read/write data plane
and frame-resync FSM live in `jvmAndroidMain`. Each port is driven by a
dedicated background thread; reads are blocking on that thread and
bridged to the engine via a coroutine `Channel` so the engine itself
stays single-writer ([ADR-002](002-architecture.md)).

## Rationale

- jSerialComm bundles native binaries for all three desktop OSes plus
  Linux/Windows on ARM, with no JNI configuration required by the consumer.
- Apache-2.0 + LGPL-3.0 dual license — compatible with our GPL-3.0
  distribution and with downstream Apache-2.0 consumers if we relicense in
  the future.
- Active maintenance (regular releases, responsive issue tracker).
- API surface is small enough to wrap behind our `Transport` interface
  without leaking jSerialComm types into `:core`.

## Alternatives considered

| Option | Why not |
|---|---|
| RXTX | Effectively unmaintained; native binaries not bundled; LGPL but no current releases. |
| PureJavaComm | No native binaries on macOS Apple Silicon; requires shipping our own JNI. |
| Java Communications API (`javax.comm`) | Sun-era; not available on modern JDKs. |
| Roll our own JNI | Dramatically out of scope for an SDK whose value is the protocol stack, not USB plumbing. |

## Consequences

### Positive
- Same wire format and reconnect logic as the Android serial path; only
  the byte-pump differs.
- Easy to test on a developer laptop with any USB-attached Meshtastic
  device.
- jSerialComm's "data listener" callback is unused — we run our own read
  loop so cancellation semantics match the rest of the engine.

### Negative / costs
- jSerialComm's blocking-read model forces a thread per active port (see
  [ADR-012](012-transport-threading.md) for the threading contract).
- Native binaries inflate the JAR; acceptable for a JVM-only sample/test
  surface, not for a publishable artifact (the transport ships, but most
  downstream apps will only consume the Android target).

### Follow-ups
- [ ] Document supported OSes and known caveats in
      `docs/manual-tests.md` whenever we hit a per-OS quirk.
- [ ] Re-evaluate every 12 months in case a maintained pure-Java
      alternative emerges.

## References

- jSerialComm: <https://fazecast.github.io/jSerialComm/>
- [`transport-serial/src/jvmMain/`](../../transport-serial/src/jvmMain/)
