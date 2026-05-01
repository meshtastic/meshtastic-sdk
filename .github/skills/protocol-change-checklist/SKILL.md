---
name: protocol-change-checklist
description: Use for any change affecting wire framing, handshake, transport semantics, session behavior, or protocol mapping.
---

# Protocol Change Checklist

Use this skill for any change affecting wire framing, handshake, transport semantics, session behavior, or protocol mapping.

## Goals
- Preserve protocol correctness across TCP, BLE, and serial.
- Preserve trust boundaries and handshake gating.
- Keep error mapping consistent with docs/error-taxonomy.md.

## Required Context
Read before editing:
- AGENTS.md
- docs/SPEC.md
- docs/protocol.md
- docs/architecture/handshake-fsm.md
- docs/decisions/002-architecture.md
- docs/error-taxonomy.md

## Guardrails
- Never mark connection as Connected before matching config_complete_id.
- Discard pre-handshake bytes as specified.
- Do not introduce shared-state synchronization primitives in engine paths.
- Keep :core dependency boundaries intact.

## Implementation Steps
1. State current behavior and target behavior.
2. Identify invariants potentially impacted.
3. Implement smallest change preserving actor flow.
4. Add/adjust tests for handshake, framing, and failure mapping.
5. Validate docs and update protocol docs when behavior changes.

## Validation Commands
- ./gradlew jvmTest
- ./gradlew check

## Deliverable
Provide:
- changed files
- invariants preserved
- tests run and outcomes
- known residual risk
