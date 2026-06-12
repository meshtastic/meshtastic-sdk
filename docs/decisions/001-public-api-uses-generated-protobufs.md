# ADR 001 — Public API uses Wire-generated protobuf types directly

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads
**Supersedes:** none
**Related:** [`../protocol.md`](../protocol.md), `meshtastic/protobufs` submodule, [Square Wire docs](https://square.github.io/wire/)

---

## Context

The Meshtastic device PhoneAPI is defined entirely by the protobuf schema vendored from `meshtastic/protobufs`. The SDK must encode/decode every message in that schema (`ToRadio`, `FromRadio`, `MeshPacket`, `Data`, `Position`, `NodeInfo`, `Telemetry`, `Config`, `ModuleConfig`, `Channel`, `User`, `AdminMessage`, …).

Two designs were considered:

1. **Hand-rolled curated domain layer.** Generate protos as `internal` DTOs; expose a parallel set of hand-written domain types (sealed `MeshMessage`, `Node`, `Position`, `Channel`, …) on the public API; map between the two in internal extension functions. Optionally re-export raw protos via a separate opt-in `:proto-raw` artifact for power users.

2. **Direct exposure of generated types.** Generate protos with Square Wire as a public artifact; consume them directly in the SDK's public API. The SDK's value-add is the *operations layer* around these types (transport, handshake, retry, ACK correlation, NodeDB caching, send helpers), not a translation layer over them.

We chose **option 2**.

## Decision

The Wire-generated protobuf types are **the** public data model of the SDK. Every public API that carries protocol payloads — `Flow<FromRadio>`, `Flow<MeshPacket>`, `nodes: Flow<NodeInfo>`, `setConfig(config: Config)`, `setChannel(channel: Channel)`, `getOwner(): User`, etc. — exposes the generated type directly.

There is **no curated domain mirror** of the protobuf schema. There is **no separate `:proto-raw` escape-hatch artifact** — the generated types *are* the API.

### What the SDK still curates

The SDK is a *client library wrapped around* the protobufs, not a *translation layer over* them. It owns and exposes its own SDK-shaped types for everything that does not exist in the proto schema:

- `RadioClient` — top-level facade, builder, lifecycle
- `ConnectionState` — sealed: `Disconnected`, `Connecting`, `Configuring`, `Connected`, `Reconnecting`
- `TransportSpec` — sealed: `Ble`, `Tcp`, `SerialAndroid`, `SerialJvm`, `Http`
- `SendState` / `SendOutcome` / `SendFailure` — sealed lifecycle types for outbound packets
- `MeshtasticException` and subclasses — for transport / programmer errors
- `MessageHandle` — handle returned from `send()` for tracking lifecycle
- `NodeChange` — delta events (`Snapshot`, `Added`, `Updated`, `Removed`) wrapping `NodeInfo`
- Value-class IDs: `NodeId`, `ChannelIndex`, `MessageId` — type-safe wrappers around the `Int`/`UInt32` fields used as IDs, used in *operation signatures* (e.g., `sendText(to: NodeId, …)`), not as replacements for protobuf fields
- High-level send helpers: `sendText(text, to, channel, replyId)`, `requestPosition(node)`, `traceRoute(dest)` — convenience over `send(packet: MeshPacket)`

### What the SDK does NOT do

- Does not hand-roll a `Position`, `Telemetry`, `Config`, `User`, `Channel`, `ChannelSettings`, `ModuleConfig`, `AdminMessage`, `RouteDiscovery`, `NeighborInfo`, etc. — Wire types are used directly.
- Does not maintain a sealed `MeshMessage` hierarchy that mirrors portnums — apps work directly with `MeshPacket` + the `payload_variant` `oneof`.
- Does not translate enums (`PortNum`, `Role`, `RebroadcastMode`, `RegionCode`, `ModemPreset`, `ConfigType`) into Kotlin re-declarations — Wire's generated `enum class` is the public type.

## Consequences

### Accepted, intentional

- **The SDK version tracks the protobuf schema.** New firmware fields, new portnums, new enum cases all flow through to consumers without SDK code changes. When the schema bumps, the SDK bumps. Pre-1.0 this is allowed freely; post-1.0 it follows SemVer (additive proto changes → minor; removals/renames → major). **This is the contract, not a problem.**
- **Consumers gain forward-compatibility for free.** `unknown_fields` is preserved on every Wire message, so a packet read from the wire, mutated in app code, and sent back round-trips losslessly even across firmware versions the SDK was not built against.
- **No shadow schema to maintain.** Every new portnum or admin message becomes available immediately on schema regeneration. The SDK does not lag the firmware.

### Risks and mitigations

| Risk | Mitigation |
|---|---|
| ABI churn on additive proto changes (constructor signature shifts in `data class`) | Pre-1.0: free hand. Post-1.0: bump minor on additive changes; binary-compat-validator gates. Document that SDK consumers should not rely on positional `data class` constructors / `componentN()` for protobuf-generated types — use named arguments and the Wire builder DSL. |
| Wire-generated types may export awkwardly to Swift (sealed `oneof` hierarchies, `okio.ByteString`, nested types) | Validated empirically in Phase 5 against a real exported XCFramework. If meaningful pain points emerge, ship a separate `sdk-swift-bridge` artifact with iOS-friendly extensions/typealiases — without changing the core API. |
| Consumers see "raw" protocol concepts (`MeshPacket.encrypted` vs `decoded`, `payload_variant` `oneof`) | Engine handles encryption transparently — `MeshPacket.decoded` is always populated on emitted packets. SDK ships KDoc and examples explaining the `payload_variant` switch idiom. |
| Lossy round-trip in cross-platform serialization (e.g., serializing `MeshPacket` to JSON for app-side persistence) | Out of scope. Apps that need durable storage use `MeshPacket.encode()` (Wire's wire-format bytes) directly. The SDK ships `:storage-sqldelight` for SDK-managed persistence (NodeDB, configs). |

### Module layout consequence

Earlier drafts of the plan included both `:proto` (internal-only DTOs) and `:proto-raw` (public escape hatch). This ADR collapses them into a **single public `:proto` module**. The SDK's `:core` artifact has `api(project(":proto"))` so consumers depending on `sdk-core` transitively resolve `sdk-proto` and can import `org.meshtastic.proto.*` directly.

```
sdk-proto      ← Wire codegen, public API.    targets: android, jvm, ios, wasmJs
sdk-core       ← RadioClient, engine, transport interface.
                          api(project(":proto"))       targets: android, jvm, ios
sdk-transport-*← per-transport implementations
```

### `:rpc` is the one exception

The future `:rpc` module (remote-engine pattern for wasm browsers / decoupled UIs) **does** ship its own coarse, snapshot+delta, versioned schema independent of the firmware protobufs. RPC is a network protocol that the SDK *publishes*, and that contract should not bump every time firmware adds a field. RPC translates between the published schema and the underlying Wire types internally. This is documented in a future ADR when `:rpc` is designed.

## Considered alternatives

### Alternative A — Hand-rolled domain layer (`MeshMessage` sealed hierarchy, `Node` / `Position` / `Config` mirrors)

Rejected. For Meshtastic specifically, the protobuf schema **is** the domain model — there is no impedance mismatch to translate across. A hand-rolled `Position(lat, lon, alt, time)` is a 1:1 mirror of the proto `Position`. Maintaining it would mean every firmware-side schema bump requires hand-editing the SDK's domain types and mapping functions; the curated layer would lag the schema and consumers would lose access to new fields until the SDK caught up. The cost is paid forever; the only benefit is a slightly tidier API surface, which Wire's KMP-native data classes already provide.

### Alternative B — `:proto` internal + `:proto-raw` public escape hatch

Rejected as gratuitous complexity. If `:proto-raw` exists, power users will reach for it (it's the only way to get raw bytes for replay / forward-compat). At that point the curated layer is bypassed for any non-trivial use case, and the SDK is paying maintenance cost on a translation layer most serious users skip. Better to have one obvious path: use the protos.

### Alternative C — Wire output as `internal`, regenerate hand-rolled mirrors

Same as A but with the `internal` modifier on the Wire output. Rejected for the same reasons; the modifier doesn't help when the underlying maintenance problem is unchanged.

## References

- Square Wire — KMP-native protobuf codegen with idiomatic Kotlin output: https://square.github.io/wire/
- `meshtastic/MQTTastic-Client-KMP` — sibling org library that exposes generated MQTT control packets directly in its public API; validated precedent.
- Critique discussion: see session checkpoint `protobuf-api-critique` (2026-04-17).
