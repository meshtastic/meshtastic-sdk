# Architecture enforcement matrix

This page records how each architectural invariant in the SDK is checked mechanically. Per [ADR-008](../decisions/008-architecture-enforcement.md) the toolset stays small: every invariant is enforced by a tool that already runs in `./gradlew check`.

A new invariant gets a row here and exactly one enforcement home; don't duplicate a rule across tools.

## Matrix

| Invariant | Source of truth | Enforced by | Where to look |
| --- | --- | --- | --- |
| `:core` declares no in-tree project dependencies (proto types come from the `org.meshtastic:protobufs` artifact) | [ADR-002](../decisions/002-architecture.md), [ADR-015](../decisions/015-consume-published-protobufs-artifact.md), [`module-graph.md`](module-graph.md) | Gradle task: `:core:verifyModuleBoundary` (wired into `check`) | [`core/build.gradle.kts`](../../core/build.gradle.kts) |
| No transport / storage code in `:core` | [ADR-002](../decisions/002-architecture.md) | Same `:core:verifyModuleBoundary` task — any project dependency fails the build | [`core/build.gradle.kts`](../../core/build.gradle.kts) |
| The engine adds no shared-state primitives (`Mutex`, `Semaphore`, `ReentrantLock`); one baselined `Mutex` in `MeshEngine.kt` predates the rule | [ADR-002](../decisions/002-architecture.md) (single-writer actor), [ADR-018](../decisions/018-engine-scoped-lock-ban.md) (scope); [ADR-003](../decisions/003-tooling.md) allows `Mutex` outside the engine | detekt `ForbiddenImport/engine` fails new imports in the files the actor runs: `MeshEngine.kt`, `EngineMessage.kt`, `MeshState.kt`, `CommandDispatcher.kt`, `WireCodec.kt` | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml) |
| Transport-side locks and atomics carry an `// ADR-012:` comment | [ADR-012](../decisions/012-transport-threading.md) | Review only; detekt cannot see comments | the transport modules |
| `kotlin.Result` is never used (public or internal) | [ADR-005](../decisions/005-api-shape.md) | detekt `ForbiddenImport` rule + Binary Compatibility Validator catches public-API leaks | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml), `api/*.api` |
| Public API surface is reviewed and stable | [ADR-005](../decisions/005-api-shape.md), [`docs/versioning.md`](../versioning.md) | Kotlin ABI validation (`./gradlew checkKotlinAbi`) | `api/jvm/*.api` and `api/*.klib.api` per module |
| Static analysis (complexity, naming, exceptions) | detekt defaults + project overrides | detekt (`./gradlew detekt`) over every source set under each module's `src/`, KMP ones included | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml), per-module `detekt-baseline.xml` |
| Agent / tooling guardrails (CODEOWNERS, AGENTS.md sync, hook policy) | Repo convention | `bash .github/tooling/check.sh` (run by pre-commit hook and CI) | [`.github/tooling/`](../../.github/tooling/) |

## Tool roles

- Gradle owns the module graph and lifecycle, so it enforces structural invariants (who can depend on whom).
- detekt runs file-level static analysis, so it enforces idiom invariants (forbidden imports, complexity, exception handling).
- Kotlin ABI validation keeps golden snapshots of every public type and member under `<module>/api/`, so it enforces surface invariants.
- Spotless with ktlint handles formatting only, never semantic rules.

There is no Konsist. The invariants the SDK needs are covered by detekt, Gradle and ABI validation, and [ADR-008](../decisions/008-architecture-enforcement.md) rules out a third engine for the same rules.

## Adding an invariant

1. Pick the tool category: structural goes to Gradle, idiom to detekt, surface to ABI validation, format to Spotless.
2. Implement the rule in that tool's config.
3. Add a row to the matrix, linking the source of truth and the rule.
4. If the rule is not obvious, write or extend an ADR explaining why, and reference it from the rule's `reason` field (detekt) or task description (Gradle).
5. Run `./gradlew check` to confirm the tree passes, and ideally add a negative test that shows the rule fails when violated.
