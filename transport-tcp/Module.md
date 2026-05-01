# Module transport-tcp

TCP/IP transport for the Meshtastic KMP SDK. Implements the `RadioTransport` interface
from `:core` using [Ktor Network](https://ktor.io/docs/client-sockets.html)
(`io.ktor:ktor-network`) for KMP socket access. This is the simplest transport — no
permissions, no platform plumbing — and is the recommended starting point for development,
testing, and the JVM `samples/cli` runner.

## Supported targets and OS versions

| Target | Backend | Minimum OS |
|---|---|---|
| Android | Ktor `aSocket().tcp()` | Android 8.0 (API 26 — repo `androidMinSdk`); requires `INTERNET` permission |
| JVM (macOS, Linux, Windows) | Ktor `aSocket().tcp()` over NIO | JDK 21 (repo baseline) |
| iOS (arm64, Simulator arm64) | Ktor `aSocket().tcp()` over POSIX sockets | iOS 13+ |
| Linux x64 (native) | Ktor `aSocket().tcp()` over POSIX sockets | glibc 2.17+ |

## Connection behavior

- **Default port:** `4403` (Meshtastic PhoneAPI; see `docs/protocol.md` §1).
- **Framing:** stream framing per `protocol.md` §2 — `0x94 0xC3 LEN_HI LEN_LO PAYLOAD`,
  big-endian uint16 length, max payload 512 bytes. The transport implements the
  `SCAN_FOR_START1 → EXPECT_START2 → READ_LEN_HI → READ_LEN_LO → READ_PAYLOAD` resync FSM
  required to recover from mid-frame garbage.
- **Wake bytes:** on connect the transport writes `4 × 0x94` before any application
  traffic (matches Meshtastic-Android's `StreamTransport.connect()`); these are valid
  no-ops for a healthy framer and corrective for a framer left mid-frame by an unclean
  prior session. See `protocol.md` §2 "Wake bytes".

## Idle timeout

The Meshtastic firmware closes any TCP connection that is idle in both directions for
**15 minutes** (`900_000 ms`). This is a device-side, non-configurable setting documented
in [`docs/protocol.md` §1A](../docs/protocol.md#-1a-tcp-idle-timeout-behavior). The
transport does not generate keep-alive frames on its own — heartbeats from the firmware
or normal mesh traffic reset the timer. When the timeout fires, Ktor surfaces the FIN as
EOF, the transport publishes `TransportState.Error(recoverable = true)`, and the engine's
liveness timeout (§6) trips shortly after.

## Reconnection strategy

`TcpTransport` itself is **stateless across connects** — `connect()` is idempotent and
can be called repeatedly after `disconnect()` or after a recoverable error. It does **not**
implement automatic reconnect or backoff; that decision belongs to the host so it can be
coordinated with UI state, network availability, and battery policy.

The recommended host policy (mirrors `protocol.md` §1A) is exponential backoff on
recoverable errors: `1s, 2s, 4s, 8s, … capped at 60s`, reset to `1s` on any successful
`Connected` transition. Errors with `recoverable = false` (currently unused on this
transport) should not be retried.

## Key packages

- `org.meshtastic.sdk.transport.tcp` — `TcpTransport(host: String, port: Int = 4403)`. The single
  public type; observes `state: StateFlow<TransportState>`, exposes
  `frames(): Flow<Frame>` (single-collector, drained by the engine), and accepts outbound
  frames via `suspend fun send(frame: Frame)`.
