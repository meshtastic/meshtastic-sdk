# Feature Specification: [FEATURE NAME]

**Feature Branch**: `[###-feature-name]`
**Created**: [DATE]
**Status**: Draft
**Input**: User description: "$ARGUMENTS"

## User Scenarios & Testing *(mandatory)*

<!--
  IMPORTANT: User stories should be PRIORITIZED as user journeys ordered by importance.
  Each user story/journey must be INDEPENDENTLY TESTABLE - meaning if you implement just ONE of them,
  you should still have a viable MVP (Minimum Viable Product) that delivers value.

  Assign priorities (P1, P2, P3, etc.) to each story, where P1 is the most critical.
  Think of each story as a standalone slice of functionality that can be:
  - Developed independently
  - Tested independently
  - Deployed independently
  - Demonstrated to users independently
-->

### User Story 1 - [Brief Title] (Priority: P1)

[Describe this user journey in plain language]

**Why this priority**: [Explain the value and why it has this priority level]

**Independent Test**: [Describe how this can be tested independently — e.g., "Can be verified by writing a `commonTest` that exercises the new API via `FakeRadioTransport` and `InMemoryStorage` from `:testing`"]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]
2. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 2 - [Brief Title] (Priority: P2)

[Describe this user journey in plain language]

**Why this priority**: [Explain the value and why it has this priority level]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

[Add more user stories as needed, each with an assigned priority]

### Edge Cases

<!--
  Consider Meshtastic-specific edge cases:
  - Device disconnection mid-operation
  - Mesh packet fragmentation / oversized payloads (228-byte text limit)
  - Channel encryption/decryption failures
  - Deferred-decrypt scenarios
  - NodeDB conflicts when multiple devices report different state
  - Transport-specific failures (BLE bond loss, TCP unreachable, serial permission denied)
  - Platform-specific behavior differences (Android vs iOS vs JVM)
-->

- What happens when [boundary condition]?
- How does system handle [error scenario]?

## Protocol Impact *(include if feature touches wire behavior)*

<!--
  Complete this section if the feature involves:
  - New or modified PhoneAPI interactions
  - MeshPacket handling changes
  - Handshake / config negotiation changes
  - Channel encryption or key management
  - NodeDB semantics
  - ACK correlation or retry behavior

  Cross-reference: docs/protocol.md is the wire-level source of truth.
  Cite meshtastic/firmware source files/lines for behavioral anchors.
-->

- **Wire behavior change**: [Describe what changes at the protocol level, or "None — pure API surface"]
- **Firmware cross-reference**: [Cite firmware source files/lines that define the canonical behavior]
- **Meshtastic-Android / Meshtastic-Apple parity**: [How do the official apps handle this? Cite source paths.]
- **Protocol doc update needed**: [Yes/No — if Yes, specify which sections of `docs/protocol.md`]

## Module Boundary Analysis *(mandatory)*

<!--
  Identify which modules this feature touches. Verify compliance with ADR-008.
  - :core depends only on :proto
  - :transport-* modules depend on :core
  - :storage-* modules depend on :core
  - No cross-transport or cross-storage dependencies
-->

| Module | Impact | New Dependencies |
|--------|--------|-----------------|
| `:proto` | [None / New proto types / Submodule bump] | [None / new Wire-generated types] |
| `:core` | [None / New public API / Internal engine change] | [Must remain :proto-only] |
| `:transport-ble` | [None / Affected / New transport feature] | [List any new deps] |
| `:transport-tcp` | [None / Affected / New transport feature] | [List any new deps] |
| `:transport-serial` | [None / Affected / New transport feature] | [List any new deps] |
| `:storage-sqldelight` | [None / Schema change / New queries] | [List any new deps] |
| `:testing` | [None / New fakes needed] | [List any new deps] |

## ABI Surface Impact *(mandatory)*

<!--
  Any change to public symbols in :core, :proto, :transport-*, :storage-sqldelight,
  :testing, or :bom requires ABI dump regeneration.
-->

- **Public API changes**: [List new/modified/removed public symbols, or "None"]
- **Breaking change**: [Yes/No — if Yes, document SemVer impact per docs/versioning.md]
- **ABI dump update required**: [Yes/No — if Yes, `./gradlew updateKotlinAbi` must be run]
- **KDoc required for new symbols**: [List symbols needing KDoc — Dokka CI gate enforces this]

## KMP Target Matrix *(include if platform-specific behavior exists)*

<!--
  Describe any platform-specific behavior. All published modules must compile for:
  jvm, androidTarget, iosArm64, iosX64, iosSimulatorArm64

  Use expect/actual for platform-specific implementations.
  No java.* or android.* imports in commonMain.
-->

| Target | Behavior | Notes |
|--------|----------|-------|
| `commonMain` | [Default/shared implementation] | [Must be pure Kotlin — no java.*/android.* imports] |
| `androidMain` | [Platform-specific behavior, if any] | [e.g., Context wiring, permission checks] |
| `jvmMain` | [Platform-specific behavior, if any] | [e.g., jSerialComm binding] |
| `iosMain` / `appleMain` | [Platform-specific behavior, if any] | [e.g., CoreBluetooth, native driver] |

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST [specific capability]
- **FR-002**: System MUST [specific capability]

*Mark unclear requirements explicitly:*

- **FR-00N**: System MUST [NEEDS CLARIFICATION: detail not specified]

### Key Entities *(include if feature involves data)*

- **[Entity 1]**: [What it represents, key attributes without implementation]
- **[Entity 2]**: [What it represents, relationships to other entities]

### Storage Impact *(include if feature involves persistence)*

<!--
  If this feature changes the SQLDelight schema:
  - New .sq queries needed?
  - Schema migration required? (see CONTRIBUTING.md § SQLDelight schema migrations)
  - Impact on existing on-disk databases?
-->

- **Schema changes**: [None / New table / Altered columns / New queries]
- **Migration required**: [Yes/No — if Yes, describe migration_N__N+1.sqm]
- **Data loss risk**: [None / Possible — describe mitigation]

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: [Measurable metric — e.g., "New API handles 100 concurrent message sends without deadlock"]
- **SC-002**: [Measurable metric — e.g., "All 5 KMP targets compile and pass tests"]
- **SC-003**: [Measurable metric — e.g., "`./gradlew check` passes with no regressions"]

## Assumptions

- [Assumption about firmware version — e.g., "Requires firmware ≥ 2.3"]
- [Assumption about scope — e.g., "wasmJs target is out of scope (post-1.0 roadmap)"]
- [Assumption about proto — e.g., "No proto submodule bump required"]
- [Platform assumption — e.g., "Android runtime permission handling is the consumer's responsibility"]
