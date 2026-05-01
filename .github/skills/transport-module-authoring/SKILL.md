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
- docs/SPEC.md
- docs/protocol.md
- docs/architecture/module-graph.md
- docs/decisions/002-architecture.md
- docs/decisions/006-multi-module-rationale.md
- docs/error-taxonomy.md

## Guardrails
- :core must not gain transport implementation dependencies.
- Transport modules should depend on :core and :proto only for contract and wire types.
- Preserve FIFO semantics for outbound frames.
- Pre-handshake bytes must not leak into ready session state.
- Keep reconnect and error behavior aligned with taxonomy and connection-state semantics.

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
