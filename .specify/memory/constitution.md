<!--
=== Sync Impact Report ===
Version change: 0.0.0 (template) → 1.0.0 (initial ratification)
Bump rationale: MAJOR — first ratification of project constitution;
  no prior version existed.

Modified principles: N/A (initial creation)

Added sections:
  - Core Principles (5 principles derived from project charter,
    versioning policy, and contributing guide)
  - Technology Constraints
  - Development Workflow
  - Governance

Removed sections: N/A

Templates requiring updates:
  - .specify/templates/plan-template.md ✅ no update needed
    (Constitution Check section is generic; gates derive at plan time)
  - .specify/templates/spec-template.md ✅ no update needed
    (requirements/scenarios structure is principle-compatible)
  - .specify/templates/tasks-template.md ✅ no update needed
    (phase structure supports test-first and modular task types)

Follow-up TODOs: none
===========================
-->

# Meshtastic SDK Constitution

## Core Principles

### I. Library-First, Multiplatform

The SDK is a **protocol library**, not an application. Every module
MUST target `commonMain` as the source of truth. Platform-specific
code (`androidMain`, `iosMain`, `jvmMain`) is permitted only to
bind a transport or storage backend — never to impose app-level
policy (no Compose, SwiftUI, Hilt, lifecycle, navigation, or
foreground-service code).

All published modules MUST compile for the canonical target set
(`jvm`, `androidTarget`, `iosArm64`, `iosX64`,
`iosSimulatorArm64`) unless a transport has no platform API on a
given target (e.g., USB-serial on iOS), in which case an empty
actual is acceptable.

**Rationale**: A single canonical KMP implementation prevents
protocol fragmentation across the Meshtastic ecosystem and allows
any host — Android app, iOS app, JVM gateway, or future browser
client — to share a single protocol layer.

### II. Protocol Fidelity

The SDK's wire-protocol implementation MUST match the behavior of
`meshtastic/firmware` as ground truth. When the canonical Android
(`Meshtastic-Android`) and Apple (`Meshtastic-Apple`) clients
disagree, firmware behavior is authoritative.

- Protobuf types MUST be generated from the vendored
  `meshtastic/protobufs` submodule via Wire. No manual proto
  modifications are permitted; bumping the submodule is the only
  mechanism by which new fields or port numbers land.
- Handshake state machines, channel encryption, NodeDB semantics,
  ACK correlation, and deferred-decrypt logic MUST be documented
  in `docs/protocol.md` and tested against device behavior.

**Rationale**: Protocol correctness is the SDK's entire value
proposition. Divergence from firmware causes silent data corruption
or connection failures that are extremely difficult to diagnose in
a mesh network.

### III. Test-First & ABI Safety

All new public API surface MUST be accompanied by tests before
implementation proceeds (red-green-refactor). Specifically:

- **Unit tests** in `commonTest` for protocol logic, state
  machines, serialization, and codec paths.
- **Contract tests** for each transport and storage interface to
  verify adapter compliance.
- **ABI validation** via Kotlin's built-in klib ABI checker
  (`checkKotlinAbi`) MUST run as a hard gate in CI from day one.
  Any PR that changes `api/*.api` files MUST commit the
  regenerated dump.
- **Manual device-conformance tests** (documented in
  `docs/manual-tests.md`) are required for transport-level
  changes that CI cannot exercise.

**Rationale**: Pre-1.0 API churn is expected, but uncontrolled
churn destroys consumer trust. ABI validation as a hard gate
ensures every breaking change is deliberate, documented, and
versioned.

### IV. Modular & Pluggable Architecture

The SDK MUST maintain a strict module boundary:

- **`:core`** — pure protocol engine with zero platform
  dependencies beyond `:proto`.
- **`:transport-*`** — one module per transport (BLE, TCP,
  serial). Each MUST implement the `Transport` interface and be
  independently consumable.
- **`:storage-*`** — pluggable persistence via the
  `StorageProvider` interface. The SDK ships
  `:storage-sqldelight` as a default but MUST NOT require it.
- **`:testing`** — in-memory fakes and `TestClock` for consumer
  test suites.

Module boundaries are enforced by
`:core:verifyModuleBoundary` in CI. No module may depend on
another module's internals. Consumers pick only the artifacts
they need.

**Rationale**: Meshtastic hosts range from resource-constrained
Android devices to headless JVM gateways. Modular artifacts
keep dependency footprint minimal and allow independent
transport evolution.

### V. Semantic Versioning & Breaking-Change Discipline

The SDK follows strict SemVer (`MAJOR.MINOR.PATCH`) derived
from annotated git tags (`vX.Y.Z`):

- **Pre-1.0**: Breaking changes are allowed between MINOR
  versions but MUST bump MINOR, regenerate `api/*.api`, add a
  `### Breaking` section to `CHANGELOG.md`, and be flagged as
  `**BREAKING**` in release notes.
- **Post-1.0**: No breaking change in any non-MAJOR release.
  Sealed-class additions and new `MeshtasticException` subtypes
  count as breaking. Deprecation cycle: deprecate at `N.M`,
  remove at `(N+1).0`.
- A Bill of Materials (`:sdk-bom`) MUST be maintained to align
  all module versions for consumers.

**Rationale**: Downstream apps and gateways depend on stable
contracts. Explicit versioning discipline prevents accidental
breakage and communicates intent clearly.

## Technology Constraints

- **Language**: Kotlin (version aligned with `mqtt-client` house
  style); JDK 21 toolchain, JDK 17 bytecode target.
- **Protobuf**: Wire 6 code generation from the vendored
  `meshtastic/protobufs` submodule. No manual proto edits.
- **Networking**: `ktor-network` (TCP), Kable (BLE),
  jSerialComm / usb-serial-for-android (serial).
- **Storage**: SQLDelight (default adapter); interface-based so
  consumers may substitute.
- **Build**: Gradle with convention plugins in `build-logic/`;
  `axion-release-plugin` for tag-driven versioning;
  Vanniktech `maven-publish-plugin` for Maven Central publishing.
- **Android**: `minSdk = 26` (Android 8.0 Oreo). No lower API
  level is supported; the SDK fails the dependency resolution at
  build time for lower targets.
- **iOS**: iOS 14+ runtime.
- **License**: GPL-3.0-or-later. All contributions MUST be
  compatible.

## Development Workflow

- **DCO (Developer Certificate of Origin)**: Every commit MUST
  be signed off (`git commit -s`). The GitHub DCO App blocks
  unsigned PRs.
- **CI gates** (all MUST pass before merge):
  1. `./gradlew check` (build + test + lint + `checkKotlinAbi`
     + detekt + `:core:verifyModuleBoundary`)
  2. ABI dump comparison (`api/*.api` files)
  3. DCO sign-off verification
- **Code review**: At least one maintainer approval required.
  Reviewers MUST verify constitution compliance — particularly
  module-boundary and breaking-change rules.
- **Documentation**: Public API changes MUST update
  `docs/api-reference.md` and relevant Dokka KDoc. Protocol
  changes MUST update `docs/protocol.md`.
- **Branching**: Feature branches off `main`; squash-merge
  preferred.

## Governance

This constitution is the supreme governance document for the
`meshtastic-sdk` project. It supersedes informal practices,
local conventions, and ad-hoc decisions.

- **Amendment procedure**: Any principle change MUST be proposed
  as a PR modifying this file, reviewed by at least one project
  lead, and merged only after consensus. The version MUST be
  bumped per the versioning rules below.
- **Versioning of this document**: MAJOR for principle removals
  or incompatible redefinitions; MINOR for new principles or
  materially expanded guidance; PATCH for clarifications and
  typo fixes.
- **Compliance review**: Every PR review MUST include a
  constitution-compliance check. The plan template's
  "Constitution Check" section operationalizes this for feature
  work.
- **Runtime guidance**: Consult `docs/` (especially
  `docs/decisions/` ADRs) for implementation-level guidance that
  supplements but does not override these principles.

**Version**: 1.0.0 | **Ratified**: 2026-05-09 | **Last Amended**: 2026-05-09
