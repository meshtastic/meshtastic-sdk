---

description: "Task list template for meshtastic-sdk feature implementation"
---

# Tasks: [FEATURE NAME]

**Input**: Design documents from `/specs/[###-feature-name]/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Test-first is required by the constitution (Principle III). Write tests in `commonTest` before implementation. Use `FakeRadioTransport` and `InMemoryStorage` from the `:testing` module for engine-level tests.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **commonMain**: `<module>/src/commonMain/kotlin/org/meshtastic/sdk/...`
- **commonTest**: `<module>/src/commonTest/kotlin/org/meshtastic/sdk/...`
- **Platform actuals**: `<module>/src/{androidMain,jvmMain,iosMain,appleMain}/kotlin/...`
- **SQLDelight schema**: `storage-sqldelight/src/commonMain/sqldelight/...`
- **ABI dumps**: `<module>/api/` (generated — never edit by hand)
- **Docs**: `docs/` (update protocol.md, api-reference.md, error-taxonomy.md as needed)

## Build & Verification Commands

| Task | Command |
|------|---------|
| Full CI gate | `./gradlew check` |
| JVM tests only | `./gradlew jvmTest` |
| iOS sim tests (macOS) | `./gradlew iosSimulatorArm64Test` |
| Module-specific tests | `./gradlew :core:jvmTest` or `./gradlew :transport-tcp:jvmTest` |
| Lint check | `./gradlew detekt spotlessCheck` |
| Auto-format | `./gradlew spotlessApply` |
| ABI check | `./gradlew checkKotlinAbi` |
| ABI dump regenerate | `./gradlew updateKotlinAbi` |
| Module boundary check | `./gradlew :core:verifyModuleBoundary` |
| SQLDelight migration verify | `./gradlew :storage-sqldelight:verifySqlDelightMigration` |
| Docs (Dokka) | `./gradlew dokkaHtmlMultiModule` |
| Agent tooling guardrails | `bash .github/tooling/check.sh` |

<!--
  ============================================================================
  IMPORTANT: The tasks below are SAMPLE TASKS for illustration purposes only.

  The /speckit.tasks command MUST replace these with actual tasks based on:
  - User stories from spec.md (with their priorities P1, P2, P3...)
  - Feature requirements from plan.md
  - Module impact matrix from plan.md
  - Entities from data-model.md
  - Contracts from contracts/

  Tasks MUST be organized by user story so each story can be:
  - Implemented independently
  - Tested independently
  - Delivered as an MVP increment

  DO NOT keep these sample tasks in the generated tasks.md file.
  ============================================================================
-->

## Phase 1: Setup & Proto (Shared Infrastructure)

**Purpose**: Project initialization, proto changes (if any), and shared infrastructure.

- [ ] T001 Verify submodule state: `git submodule status` — ensure `proto/src/protobufs` is at expected commit
- [ ] T002 [P] If proto submodule bump needed: update submodule, run `./gradlew updateKotlinAbi`, commit generated files
- [ ] T003 [P] Create feature branch: `git checkout -b [###-feature-name]`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure in `:core` that MUST be complete before user story implementation.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete. All code in `commonMain` unless platform-specific.

- [ ] T004 Define new public types/interfaces in `core/src/commonMain/kotlin/org/meshtastic/sdk/[Type].kt` — add KDoc
- [ ] T005 [P] Add test fakes to `:testing` module if needed: `testing/src/commonMain/kotlin/org/meshtastic/sdk/testing/[Fake].kt`
- [ ] T006 [P] If storage schema changes: add migration `storage-sqldelight/src/commonMain/sqldelight/migration_N__N+1.sqm`
- [ ] T007 Run `./gradlew spotlessApply` to fix formatting

**Checkpoint**: Foundation ready — `./gradlew :core:jvmTest :testing:jvmTest` passes

---

## Phase 3: User Story 1 - [Title] (Priority: P1) 🎯 MVP

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify — e.g., "Run `./gradlew :core:jvmTest --tests '*[TestClass]*'`"]

### Tests for User Story 1 ⚠️

> **NOTE: Write these tests FIRST (Principle III), ensure they FAIL before implementation**

- [ ] T008 [P] [US1] Unit test in `core/src/commonTest/kotlin/org/meshtastic/sdk/[Feature]Test.kt` — test against `FakeRadioTransport`
- [ ] T009 [P] [US1] Flow/state test in `core/src/commonTest/kotlin/org/meshtastic/sdk/[Feature]FlowTest.kt` — use Turbine

### Implementation for User Story 1

- [ ] T010 [US1] Implement engine logic in `core/src/commonMain/kotlin/org/meshtastic/sdk/internal/[Impl].kt`
- [ ] T011 [US1] Wire public API in `core/src/commonMain/kotlin/org/meshtastic/sdk/[PublicApi].kt` — add KDoc
- [ ] T012 [US1] If transport-specific: implement in `transport-[type]/src/commonMain/kotlin/org/meshtastic/sdk/transport/[type]/[Impl].kt`
- [ ] T013 [US1] Verify tests pass: `./gradlew :core:jvmTest`

**Checkpoint**: User Story 1 is functional — `./gradlew :core:jvmTest` green

---

## Phase 4: User Story 2 - [Title] (Priority: P2)

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify]

### Tests for User Story 2 ⚠️

- [ ] T014 [P] [US2] Unit test in `core/src/commonTest/kotlin/org/meshtastic/sdk/[Feature]Test.kt`

### Implementation for User Story 2

- [ ] T015 [US2] Implement in appropriate module (`core/`, `transport-*/`, `storage-sqldelight/`)
- [ ] T016 [US2] Add KDoc to all new public symbols
- [ ] T017 [US2] Verify: `./gradlew :core:jvmTest`

**Checkpoint**: User Stories 1 AND 2 both pass independently

---

[Add more user story phases as needed, following the same pattern]

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Finalize quality gates and documentation.

- [ ] TXXX [P] Run full ABI check: `./gradlew checkKotlinAbi` — if failing, run `./gradlew updateKotlinAbi` and commit `api/*.api` files
- [ ] TXXX [P] Run module boundary verification: `./gradlew :core:verifyModuleBoundary`
- [ ] TXXX [P] Run full lint: `./gradlew spotlessApply detekt`
- [ ] TXXX [P] If storage schema changed: `./gradlew :storage-sqldelight:verifySqlDelightMigration`
- [ ] TXXX [P] Update documentation:
  - `docs/api-reference.md` — if public API changed
  - `docs/protocol.md` — if wire behavior changed
  - `docs/error-taxonomy.md` — if new error types added
  - `docs/manual-tests.md` — if transport-level changes need device verification
  - `CHANGELOG.md` — add entry under appropriate section (`### Breaking`, `### Added`, `### Changed`, `### Fixed`)
- [ ] TXXX Run `./gradlew dokkaGeneratePublicationHtml` — verify no missing KDoc (Dokka CI gate)
- [ ] TXXX Run full CI gate: `./gradlew check`
- [ ] TXXX Run agent tooling guardrails: `bash .github/tooling/check.sh`
- [ ] TXXX Run quickstart.md validation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup & Proto (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Phase 2 completion
  - User stories can proceed in parallel if they touch different modules
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### Module-Level Parallelism

- Changes to different modules (e.g., `:transport-tcp` vs `:storage-sqldelight`) can proceed in parallel
- Changes to `:core` internals should be serialized to avoid merge conflicts in the actor
- Proto submodule bumps must land first since all modules transitively depend on `:proto`

### Within Each User Story

- Tests MUST be written and FAIL before implementation (Principle III)
- Internal engine logic before public API wiring
- Core implementation before transport/storage integration
- KDoc before ABI dump
- Story-level verification before moving to next priority

### Parallel Opportunities

- All tasks marked [P] within the same phase can run in parallel
- Different user stories touching different modules can run in parallel
- Tests for the next story can be written while the current story is being implemented

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup & Proto
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: `./gradlew check` — all gates green
5. PR-ready after this point

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add User Story 1 → `./gradlew check` green → PR-ready (MVP!)
3. Add User Story 2 → `./gradlew check` green → Updated PR
4. Polish phase → Final `./gradlew check` → Ready for review

---

## Notes

- [P] tasks = different files/modules, no dependencies
- [Story] label maps task to specific user story for traceability
- Sign all commits with DCO: `git commit -s`
- Conventional Commits encouraged: `feat(core):`, `fix(transport-tcp):`, `test(engine):`
- Run `./gradlew spotlessApply` before committing — ktlint enforced
- Never edit `api/*.api` files by hand — use `./gradlew updateKotlinAbi`
- Proto fields are `snake_case` (e.g., `user.long_name`) — do not rename
- No `Dispatchers.IO` from `commonMain` — use `expect/actual` per-platform dispatchers
- Transport-side locks must carry `// ADR-012:` annotation
