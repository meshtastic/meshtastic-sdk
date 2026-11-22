<!--
=== Sync Impact Report ===
Version change: 1.0.0 → 2.0.0
Bump rationale: MAJOR. Principle II redefines how protobuf types
  are sourced (published artifact per ADR-015, not a vendored
  submodule), which a change compliant with 1.0.0 would violate.

Modified principles:
  - I: the canonical target set adds js and wasmJs for :core,
    :testing and :transport-websocket (ADR-017).
  - II: protobuf types come from org.meshtastic:protobufs; the
    device, not the SDK, decrypts mesh traffic.
  - IV: :core depends only on the protobufs artifact; transports
    implement RadioTransport; WebSocket joins the transport list.
  - V: breaking changes are recorded as changelog.d fragments.

Other changes: Technology Constraints and Development Workflow
  match the above; documentation duty is KDoc plus the ABI dumps;
  Spec Kit lifecycle references removed with its scaffolding.

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
actual is acceptable. `:core`, `:testing` and
`:transport-websocket` MUST also compile for `js` and `wasmJs`,
where a web host runs the engine in-process (ADR-017).

**Rationale**: A single canonical KMP implementation prevents
protocol fragmentation across the Meshtastic ecosystem and allows
any host — Android app, iOS app, JVM gateway, or future browser
client — to share a single protocol layer.

### II. Protocol Fidelity

The SDK's wire-protocol implementation MUST match the behavior of
`meshtastic/firmware` as ground truth. When the canonical Android
(`Meshtastic-Android`) and Apple (`Meshtastic-Apple`) clients
disagree, firmware behavior is authoritative.

- Protobuf types MUST come from the published
  `org.meshtastic:protobufs` artifact, pinned in
  `gradle/libs.versions.toml` (ADR-015). There is no submodule and
  no in-tree proto module; bumping the pin is the only mechanism
  by which new fields or port numbers land.
- Handshake state machines, channel encryption, NodeDB semantics,
  ACK correlation, and the handling of packets the device could not
  decrypt MUST be documented in `docs/protocol.md` and tested
  against device behavior.

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

- **`:core`**: pure protocol engine with no project
  dependency; its only proto dependency is the published
  `org.meshtastic:protobufs` artifact.
- **`:transport-*`**: one module per transport (BLE, TCP,
  serial, WebSocket). Each MUST implement the `RadioTransport`
  interface and be independently consumable.
- **`:storage-*`**: pluggable persistence via the
  `StorageProvider` interface. The SDK ships
  `:storage-sqldelight` as a default but MUST NOT require it.
- **`:testing`**: in-memory fakes and `TestClock` for consumer
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
  versions but MUST bump MINOR, regenerate the ABI dumps, add a
  `changelog.d/<pr>.breaking.md` fragment (folded into
  `### Breaking` by `scripts/changelog.sh`), and be flagged as
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
- **Protobuf**: Wire-generated types from the published
  `org.meshtastic:protobufs` artifact (ADR-015). No manual proto
  edits.
- **Networking**: `ktor-network` (TCP), Ktor client WebSockets
  (WebSocket), Kable (BLE), jSerialComm / usb-serial-for-android
  (serial).
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
- **Documentation**: Public API changes MUST carry KDoc and
  commit the regenerated ABI dumps (`updateKotlinAbi`, checked by
  `checkKotlinAbi`). Protocol changes MUST update
  `docs/protocol.md`.
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
  constitution-compliance check.
- **Runtime guidance**: Consult `docs/` (especially
  `docs/decisions/` ADRs) for implementation-level guidance that
  supplements but does not override these principles.

**Version**: 2.0.0 | **Ratified**: 2026-05-09 | **Last Amended**: 2026-11-20
