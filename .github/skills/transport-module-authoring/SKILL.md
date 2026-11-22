---
name: transport-module-authoring
description: Use when implementing a new transport module or making non-trivial transport behavior changes without violating module and engine architecture.
---

# Transport Module Authoring

Use this skill when implementing a new transport module or making non-trivial transport behavior changes.

## Goals
- Add transport capabilities without violating module and engine architecture.
- Keep wire behavior compatible with protocol and handshake requirements.
- Ensure failure mapping and lifecycle semantics remain predictable.

## Required Context
- AGENTS.md
- docs/protocol.md
- docs/architecture/module-graph.md
- docs/architecture/engine-actor.md
- docs/architecture/transport-comparison.md
- docs/decisions/002-architecture.md
- docs/decisions/006-multi-module-rationale.md
- docs/error-taxonomy.md
- The `RadioTransport` KDoc in core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt

## Guardrails
- :core must not gain transport implementation dependencies; `:core:verifyModuleBoundary` fails the build if :core declares any project dependency.
- Transport modules depend on :core only; the wire types arrive transitively through it.
- Preserve FIFO semantics for outbound frames.
- Pre-handshake bytes must not leak into ready session state.
- Keep reconnect and error behavior aligned with taxonomy and connection-state semantics.

## New module checklist
- `include` the module in settings.gradle.kts and apply `meshtastic.kmp.library` and `meshtastic.publishing`, plus the platform plugins for its targets.
- Implement `RadioTransport`: `frames()` is cold with one collector, `disconnect()` is idempotent and tolerated at any point, and `state` reports `TransportState.Error(recoverable)`, which decides whether auto-reconnect retries.
- Add a `Module.md` (Dokka includes it), add the module to `libraryModules` in the root build.gradle.kts and to bom/build.gradle.kts, and commit its ABI dump with `updateKotlinAbi`.
- Update docs/architecture/module-graph.md and transport-comparison.md.

## Workflow
1. Capture expected transport lifecycle and framing model.
2. Map lifecycle to RadioTransport contract methods and events.
3. Implement minimal adapter and conversion logic.
4. Add tests for connection lifecycle, send ordering, receive parsing, and error translation.
5. Verify cross-platform assumptions for target matrix.
6. Update docs when transport behavior changes observable contracts.

## Validation Commands
- ./gradlew jvmTest
- ./gradlew check

## Deliverable
- Changed files summary
- Contract compatibility verification
- Tests run and outcomes
- Remaining transport-specific risks
