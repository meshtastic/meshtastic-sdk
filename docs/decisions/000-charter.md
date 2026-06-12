# ADR 000 — Charter: scope, non-goals, and relationship to sibling Meshtastic libraries

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads (Meshtastic org)
**Supersedes:** none
**Related:** [`../SPEC.md`](../SPEC.md), [`../protocol.md`](../protocol.md), [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client), [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android), [`meshtastic/Meshtastic-Apple`](https://github.com/meshtastic/Meshtastic-Apple)

---

## Context

The Meshtastic ecosystem already has:

- **`Meshtastic-Android`** — a full first-party Android *application* with embedded protocol code in `core/network`, `core/data`, etc. Tightly coupled to Android lifecycle, Hilt, WorkManager, Compose.
- **`Meshtastic-Apple`** — a full first-party iOS/macOS *application* with embedded protocol code in `Meshtastic/Accessory/...`. Tightly coupled to SwiftUI, CoreData, CoreBluetooth.
- **`mqtt-client`** (KMP) — a Meshtastic-org Kotlin Multiplatform MQTT client library used by the protocol's MQTT side-channel and external bridges. **Library, not an app.** Establishes the org's KMP house style.
- **`firmware`** — the C++ device-side reference, consulted as ground truth for protocol behavior.

There is **no** library shared between the Android and Apple apps. Every new client (desktop, web, headless gateway, third-party Android app) re-implements the wire protocol from scratch, usually by reading and rewriting fragments of the two flagship apps. That fragments protocol coverage, dilutes review, and slows new feature adoption (e.g., a wire-level change requires N parallel rewrites).

## Decision

`meshtastic-sdk` is a **Kotlin Multiplatform SDK** that owns the *device-side wire protocol* as a reusable library. It is the single canonical Kotlin/JVM/iOS implementation of the Meshtastic PhoneAPI for MVP, with a `wasmJs` (browser, RPC-client-only) target on the post-1.0 roadmap once supporting tooling matures (see [`../future/wasm-rpc-roadmap.md`](../future/wasm-rpc-roadmap.md)).

### Targets

MVP: `androidTarget()`, `jvm()`, `iosArm64()`, `iosX64()`, `iosSimulatorArm64()` — per-module subset (see ADR-006). `wasmJs()` is roadmap, not MVP.

### What this SDK IS

1. **A protocol library.** Connects to a Meshtastic device over BLE / TCP / Serial / HTTP, performs the two-stage handshake (`docs/protocol.md` §6), exchanges `ToRadio`/`FromRadio`, and exposes the resulting state as a coroutine-friendly API.
2. **The canonical Meshtastic Kotlin codec.** Wire-generated protobuf types from the vendored `meshtastic/protobufs` schema are the public data model (ADR-001). Bumping the proto submodule and regenerating ships every new field/portnum to consumers automatically.
3. **Multiplatform-first.** `commonMain` is the source of truth; platform code is the minimum needed to bind a transport (Kable for BLE, Ktor sockets for TCP, jSerialComm/usb-serial-for-android for serial).
4. **Engine-glue only on hosts.** Hosts plug in storage and call `connect()` / `send()` / observe flows. No app-policy code.

### What this SDK is NOT

1. **Not an app, not a UI library, not a navigation library, not a DI framework.** No Compose, SwiftUI, Hilt, Koin, AndroidX lifecycle, foreground services, notifications, or WorkManager in the SDK. Hosts wire those up.
2. **Not the org's MQTT client.** The MQTT *external broker* relationship is owned by [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client). This SDK's MQTT support is limited to the **device-as-MQTT-proxy** mode (`MqttClientProxyMessage` over PhoneAPI; `protocol.md` §14), where the device is the MQTT speaker and the phone just relays bytes. If a host wants direct broker access, it consumes `mqtt-client` separately.
3. **Not a port of `Meshtastic-Android` or `Meshtastic-Apple`.** Both apps remain first-party flagship clients; over time they MAY adopt this SDK as their protocol layer, but that's a downstream choice, not a charter goal. We cross-validate against both reference clients when behavior is unclear.
4. **Not a UI / persistence opinion.** Storage is pluggable (`StorageProvider` interface); the SDK ships an `:storage-sqldelight` adapter as a sensible default but never requires it.
5. **Not backward-compatible with anything.** Greenfield. Pre-1.0 we break the API freely (with `updateKotlinAbi` + CHANGELOG); 1.0+ binary-compatibility-validator hard-gates ABI.

### Relationship to siblings

| Project | Relationship | What `meshtastic-sdk` does NOT do |
|---|---|---|
| `meshtastic/protobufs` | **Vendored** as `proto/src/protobufs` git submodule. Wire generates Kotlin from it. | We do not fork or modify the schema. Bumping the submodule is the only way fields land. |
| `meshtastic/firmware` | **Read-only behavior reference.** Ground truth when Apple and Android disagree. | We do not embed firmware code, schemas other than `protobufs`, or build artifacts. |
| `meshtastic/Meshtastic-Android` | **Cross-validation reference.** We consult its `core/network` for how the canonical Android client implements the protocol. As an org-internal codebase we may also lift idiomatic snippets directly. | We do not depend on it; it does not depend on us (yet). |
| `meshtastic/Meshtastic-Apple` | **Cross-validation reference**, especially for transport `requiresPeriodicHeartbeat` semantics, accessory framing, and the canonical Swift bridging surface. | Same as above. |
| `meshtastic/mqtt-client` | **Sibling KMP library.** Establishes the house style we follow (Kotlin version, Wire 6, kotlinx-io *(byte-string alignment since reversed in 0.2.0 — `okio.ByteString`; see ADR-003 superseded notes)*, Ktor, axion-release; see ADR-003). | We do not implement broker-side MQTT; consumers depending on broker-side MQTT add `mqtt-client` themselves. |

## Alternatives considered

- **Extract directly from `Meshtastic-Android`'s `:core` modules.** Rejected: those modules are tightly coupled to Hilt, Android lifecycle, and Kotlin stdlib paths that don't survive a multiplatform move. A focused extraction is more work than a clean-sheet KMP design that consults Android as one of several references.
- **Make this an Android library only and add iOS later.** Rejected: KMP-from-day-one is the org-wide direction (`mqtt-client` already proves it works). Bolt-on multiplatform later means re-litigating every API decision when iOS shows poor bridging.
- **Combine MQTT broker support into this SDK.** Rejected: `mqtt-client` already exists with a focused scope. Folding it in would conflate two protocols (PhoneAPI + MQTT) and force consumers who only need the device link to also pay the broker dependency cost.

## Consequences

- **Immediate scope is large but bounded.** §3 of `SPEC.md` enumerates the complete public API; §6 of `SPEC.md` partitions it into phases.
- **Two parallel apps remain authoritative until they migrate.** Until and unless `Meshtastic-Android` / `Meshtastic-Apple` adopt this SDK, we treat them as references, not consumers.
- **The org accepts a third Meshtastic-protocol implementation.** This is the explicit cost. The trade is: a single canonical KMP implementation that future apps (and the existing flagships, eventually) can converge onto.
- **The proto submodule is the only gated dependency on upstream.** Bumping it is the integration point for every new firmware feature.
