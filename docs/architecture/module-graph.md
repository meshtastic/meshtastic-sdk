# Module dependency graph

> Visual companion to [`../decisions/006-multi-module-rationale.md`](../decisions/006-multi-module-rationale.md). The graph is enforced by the Gradle build (`:core:verifyModuleBoundary`) and detekt.
>
> **`RadioTransport`, `Frame`, and `TransportIdentity` live in `:core`** (per `SPEC.md` §3.4) — there is no `:transport-api` module. Transport implementation modules depend directly on `:core` for the interface and on `:proto` for the wire types.

## MVP library modules

```mermaid
flowchart TB
    classDef pub fill:#dff7df,stroke:#2a8a2a;
    classDef external fill:#eee,stroke:#888,stroke-dasharray: 4 4;

    proto[":proto<br/>(Wire-generated)"]:::pub
    core[":core<br/>RadioClient, engine,<br/>RadioTransport interface"]:::pub

    ble[":transport-ble<br/>(Kable)"]:::pub
    tcp[":transport-tcp<br/>(Ktor sockets)"]:::pub
    serial[":transport-serial<br/>(jSerialComm; Android via SerialPort.fromAndroidPort)"]:::pub

    storageSql[":storage-sqldelight"]:::pub

    testing[":testing<br/>(InMemoryStorage, fakes)"]:::pub
    bom[":bom<br/>(version alignment)"]:::pub

    extProtobufs[("meshtastic/<br/>protobufs")]:::external

    extProtobufs -->|submodule| proto

    proto --> core

    proto --> ble
    core --> ble
    proto --> tcp
    core --> tcp
    proto --> serial
    core --> serial

    core --> storageSql
    proto --> storageSql

    core --> testing
    proto --> testing
```

**Read the arrows as `produces input for`** (i.e., `:proto → :core` means `:core` depends on `:proto`).

### Hard rules (Gradle + detekt enforced)

1. `:core` depends on `:proto`. **Nothing else.** It defines the `RadioTransport` interface itself.
2. Transport modules depend on `:core` (for `RadioTransport` + `Frame` + `TransportIdentity`) and `:proto` (for wire payloads). They are wired into a `RadioClient` via `Builder.transport(...)` at construction time.
3. Storage modules depend on `:core` (for `StorageProvider` + `DeviceStorage`) and `:proto`.
4. `:testing` may depend on anything published.
5. `:samples/*` may depend on anything; **nothing depends on `:samples/*`**; samples are not published.
6. `:bom` is a leaf — it lists every published artifact's coordinate but compiles to a Maven BOM only.

## MVP per-target compile matrix

| Module | android | jvm | iosArm64 | iosX64 | iosSimulatorArm64 |
|---|:---:|:---:|:---:|:---:|:---:|
| `:proto` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:core` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:transport-ble` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:transport-tcp` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:transport-serial` | ✓ | ✓ | stub | stub | stub |
| `:storage-sqldelight` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:testing` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `:bom` | n/a (Maven BOM only) | | | | |

**iOS posture:** the iOS targets of `:transport-serial` ship as headers/empty actuals only — iOS does not expose USB-serial without an MFi accessory + bespoke framing, so consumers should not attempt `JvmSerialPorts` / `AndroidSerialPorts` on Apple targets. All other transports (BLE, TCP) work on iOS. `:transport-ble` ships a JVM artifact in addition to Android/iOS — Kable's JVM backend powers macOS/Linux/Windows hosts; treat it as best-effort across desktop OSes.
**JVM posture:** BLE is available via `Kable`'s JVM backend (macOS/Linux/Windows); treat as best-effort on desktop OSes (same as line above). Serial uses jSerialComm.
**Android posture:** serial uses usb-serial-for-android via the same `:transport-serial` artifact (single KMP module, two engines wired via expect/actual).

## Sample apps (out of band, not published)

```mermaid
flowchart LR
    cli[":samples/cli (jvm TUI + probes)"] --> tcp[":transport-tcp"]
    cli --> ble[":transport-ble"]
    cli --> serial[":transport-serial"]
    cli --> sqlstore[":storage-sqldelight"]
```

`:samples/cli` is currently the only sample. It exercises all three transports
plus persistent storage from a single JVM entry point with a Mosaic-rendered
TUI dashboard and headless probe sub-commands (`tcpprobe`, `bleprobe`,
`serialprobe`) used as connection-robustness regression harnesses.

A minimal SwiftUI sample for iOS distribution validation will return in
Phase 5; the previous Compose Multiplatform `:samples:app` and SwiftUI
`samples/iosApp/` were removed (see CHANGELOG `[Unreleased] / Removed`).

Sample-specific scope and acceptance criteria live in [`../samples.md`](../samples.md).

## Roadmap (post-1.0, not in MVP)

The following modules and the `wasmJs` target are tracked separately in [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md) and are NOT part of the MVP graph above:

```mermaid
flowchart TB
    classDef future fill:#fff7d6,stroke:#aa8a00,stroke-dasharray: 3 3;

    rpc[":rpc<br/>wire envelopes"]:::future
    transportRpc[":transport-rpc<br/>(Ktor WS client)"]:::future
    hostRpc[":host-rpc-server<br/>(JVM/Android)"]:::future
    transportMqttProxy[":transport-mqtt-proxy<br/>(MqttClientProxyMessage)"]:::future
    wasmApp[":samples/wasm-app<br/>(wasmJs)"]:::future

    transportRpc -. WS .-> hostRpc
    wasmApp --> transportRpc
```

| Module | Targets (planned) | Depends on | Roadmap link |
|---|---|---|---|
| `:rpc` | all incl. `wasmJs` | `:proto` | wasm-rpc-roadmap |
| `:transport-rpc` | all incl. `wasmJs` | `:rpc`, `:proto` (NOT `:core`) | wasm-rpc-roadmap |
| `:host-rpc-server` | jvm, android | `:core`, `:rpc` | wasm-rpc-roadmap |
| `:transport-mqtt-proxy` | android, jvm, ios | `:core`, `:proto`, `org.meshtastic:mqtt-client` | SPEC §6 Phase 6 |
| `wasmJs` source set on `:proto` and `:core` | — | — | wasm-rpc-roadmap |

These do not exist in the MVP graph; they are a destination, not a current state.

## Related

- ADR-006 — module rationale.
- ADR-002 — engine architecture (justifies the `:core` ↔ transport boundary).
- ADR-003 — tooling (justifies which library each transport module wraps).
- `transport-isolation.md` — deep dive on why transports are separate modules, error handling differences, and how to add a new transport.
- `transport-comparison.md` — feature × transport matrix (TCP / BLE / serial), OS support, and selection guidance.
- [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md) — post-1.0 wasm + RPC architecture.
