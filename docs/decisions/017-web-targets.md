# ADR-017: js and wasmJs targets run the engine in-process

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-20 |
| **Deciders** | Maintainers |
| **Supersedes** | The "`wasmJs` is post-1.0" consequence of [ADR-003](003-tooling.md) |
| **Related** | [ADR-006](006-multi-module-rationale.md), [`docs/architecture/module-graph.md`](../architecture/module-graph.md), [`docs/future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md) |

## Context

ADR-003 left `wasmJs` for after 1.0, on two grounds: the engine's dependencies were not ready for wasm, and a browser could not reach a radio, so a web host would need a remote engine over RPC.

Both have changed. Every dependency of `:core`, `:testing` and `:transport-websocket` publishes a `wasmJs` variant at the pinned version (`org.meshtastic:protobufs`, Wire runtime, kotlinx-coroutines, atomicfu, okio, turbine, Ktor's JS engine). Firmware serves a WebSocket phone API that a browser can open directly. Compose Multiplatform for web builds for `wasmJs`, so a Compose web app on the SDK needs that target, not `js`.

## Decision

`:core`, `:testing` and `:transport-websocket` publish `js` and `wasmJs` klibs, and a web host runs the engine in-process.

The `meshtastic.js.library` convention plugin adds both targets, with tests on Node.js and Node taken from `PATH`. The websocket module's one platform file, its default `HttpClient`, lives in `webMain` and serves both targets through Ktor's JS engine over the platform `WebSocket`. `:transport-tcp`, `:transport-ble`, `:transport-serial` and `:storage-sqldelight` stay off the web targets.

## Rationale

- The engine is pure common code, so the web targets cost one shared source file and no API change; the klib ABI dumps differ only in their target list.
- In-process keeps one engine and one API on every target. The remote-RPC adapter stays on the roadmap for hosts whose radio the browser cannot reach.

## Alternatives considered

| Option | Why not |
|---|---|
| `js` only | Compose Multiplatform for web builds for `wasmJs`; a `js` klib cannot be consumed there. |
| Wait for the RPC adapter | It adds a server, a wire protocol and versioning a browser does not need when the radio speaks WebSocket. |

## Consequences

### Positive
- Kotlin/JS and Kotlin/Wasm hosts, Compose for web among them, use the SDK with no server.

### Negative / costs
- Two more test targets in CI, run on Node.js only; no browser run.
- On both web targets Ktor's JS engine cannot cap the WebSocket frame size, so an oversized message is buffered before the transport rejects it.
