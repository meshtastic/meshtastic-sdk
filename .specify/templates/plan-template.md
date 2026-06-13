# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]
**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

**Language/Version**: Kotlin 2.3.x (KMP), JDK 21 toolchain, JDK 17 bytecode target
**Build System**: Gradle with convention plugins (`build-logic/`), axion-release versioning
**Primary Dependencies**: Wire 6 (protobuf), kotlinx-coroutines, Okio (Wire's runtime), Kable (BLE), ktor-network (TCP)
**Storage**: SQLDelight 2.x (`:storage-sqldelight`); interface-based (`StorageProvider`) — consumers may substitute
**Testing**: kotlin-test, Turbine (Flow testing), Kotest assertions, coroutines-test; fakes in `:testing` module
**Target Platforms**: JVM, Android (minSdk 26), iOS (Arm64, X64, SimulatorArm64)
**Project Type**: Multiplatform SDK library
**Constraints**: `:core` depends only on `:proto`; no `java.*`/`android.*` in `commonMain`; actor-based engine (no mutex/synchronized); KDoc on every public symbol
**CI Gates**: `./gradlew check` (build + test + lint + `checkKotlinAbi` + detekt + `:core:verifyModuleBoundary`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Library-First, Multiplatform | ☐ | All code in `commonMain`; platform-specific only for transport/storage binding |
| II. Protocol Fidelity | ☐ | Wire behavior matches firmware; proto types from vendored submodule only |
| III. Test-First & ABI Safety | ☐ | Tests in `commonTest` before implementation; `checkKotlinAbi` green |
| IV. Modular & Pluggable Architecture | ☐ | Module boundaries respected; `:core` → `:proto` only |
| V. Semantic Versioning | ☐ | Breaking changes flagged; ABI dump regenerated if public API changed |

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (KMP multi-module layout)

<!--
  Map the feature to the actual module structure below.
  Delete modules not affected. Expand with real paths for affected modules.
-->

```text
# Protocol types (generated — rarely touched directly)
proto/
├── src/protobufs/                    # Vendored meshtastic/protobufs submodule
└── build/generated/wire/commonMain/  # Wire-generated Kotlin types

# Core engine (public API + internal actor)
core/
├── src/commonMain/kotlin/org/meshtastic/sdk/
│   ├── [PublicType].kt               # Public API surface (KDoc required)
│   └── internal/                     # Engine internals (actor, state, codecs)
├── src/commonTest/kotlin/            # Unit tests (kotlin-test + Turbine + Kotest)
├── src/jvmTest/kotlin/               # JVM-specific tests
└── api/                              # ABI dumps (generated — never edit by hand)

# Transport modules (one per transport)
transport-ble/src/commonMain/kotlin/org/meshtastic/sdk/transport/ble/
transport-tcp/src/commonMain/kotlin/org/meshtastic/sdk/transport/tcp/
transport-serial/src/commonMain/kotlin/org/meshtastic/sdk/transport/serial/

# Storage
storage-sqldelight/
├── src/commonMain/sqldelight/        # .sq schema + queries
├── src/commonMain/kotlin/            # StorageProvider implementation
└── src/commonMain/sqldelight/databases/  # Schema snapshots for migration verification

# Test fakes
testing/src/commonMain/kotlin/        # InMemoryStorage, FakeRadioTransport, TestClock

# Samples
samples/cli/                          # JVM CLI sample
samples/parity-app/                   # Compose Multiplatform sample
samples/parity-android-app/           # Android Compose sample
```

**Structure Decision**: [Document which modules are affected and reference the real directories above]

## Module Impact Matrix

<!--
  For each affected module, describe what changes and any new dependencies.
  Verify no module boundary violations (ADR-008).
-->

| Module | Changes | New Dependencies | ABI Impact |
|--------|---------|-----------------|------------|
| `:proto` | [None / Submodule bump / N/A] | [Wire-generated only] | [None / New types] |
| `:core` | [Describe changes] | [Must remain :proto-only] | [New public API? Breaking?] |
| `:transport-*` | [Which transports affected] | [Must depend on :core only] | [Surface changes?] |
| `:storage-sqldelight` | [Schema change? New queries?] | [Must depend on :core only] | [Migration needed?] |
| `:testing` | [New fakes needed?] | [Depends on :core] | [New test utilities?] |

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., platform import in commonMain] | [current need] | [why expect/actual insufficient] |
