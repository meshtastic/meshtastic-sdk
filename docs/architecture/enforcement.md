# Architecture Enforcement Matrix

This document records *how* each architectural invariant in the SDK is
mechanically checked. Per [ADR-008](../decisions/008-architecture-enforcement.md),
we deliberately keep the toolset small: every invariant is enforced by a tool
that already runs as part of `./gradlew check`.

If you add a new invariant, add a row here and pick exactly one enforcement
home — do not duplicate the same rule across tools.

## Matrix

| Invariant | Source of truth | Enforced by | Where to look |
| --- | --- | --- | --- |
| `:core` declares no in-tree project dependencies (proto types come from the `org.meshtastic:protobufs` artifact) | [ADR-002](../decisions/002-architecture.md), [ADR-015](../decisions/015-consume-published-protobufs-artifact.md), [`module-graph.md`](module-graph.md) | Gradle task: `:core:verifyModuleBoundary` (wired into `check`) | [`core/build.gradle.kts`](../../core/build.gradle.kts) |
| No transport / storage code in `:core` | [ADR-002](../decisions/002-architecture.md) | Same `:core:verifyModuleBoundary` task — any project dependency fails the build | [`core/build.gradle.kts`](../../core/build.gradle.kts) |
| Engine paths use no shared-state primitives (`Mutex`, `Semaphore`, `ReentrantLock`) | [ADR-002](../decisions/002-architecture.md) — single-writer actor | detekt `ForbiddenImport` rule (`style.ForbiddenImport`) | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml) |
| `kotlin.Result` is never used (public or internal) | [ADR-005](../decisions/005-api-shape.md) | detekt `ForbiddenImport` rule + Binary Compatibility Validator catches public-API leaks | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml), `api/*.api` |
| Public API surface is reviewed and stable | [ADR-005](../decisions/005-api-shape.md), [`docs/versioning.md`](../versioning.md) | Kotlinx Binary Compatibility Validator (`./gradlew checkKotlinAbi`) | `api/jvm/*.api`, `api/android/*.api` per module |
| Static analysis (complexity, naming, exceptions) | detekt defaults + project overrides | detekt (`./gradlew detekt`) | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml), per-module `detekt-baseline.xml` |
| Agent / tooling guardrails (CODEOWNERS, AGENTS.md sync, hook policy) | Repo convention | `bash .github/tooling/check.sh` (run by pre-commit hook and CI) | [`.github/tooling/`](../../.github/tooling/) |

## Tool Roles

- **Gradle** — module dependency graph and lifecycle. Best place to enforce
  *structural* invariants (who can depend on whom).
- **detekt** — file-level static analysis. Best place to enforce *idiom*
  invariants (forbidden imports, complexity, exception handling).
- **Binary Compatibility Validator (BCV)** — golden snapshots of every public
  type and member. Best place to enforce *surface* invariants. Generated
  baselines live under `<module>/api/`.
- **Spotless + ktlint** — formatting only. Never used for semantic rules.

## Why Not Konsist

Konsist was previously listed in the version catalog and referenced from
`AGENTS.md` and `CONTRIBUTING.md`, but no Konsist test was ever written. The
invariants we actually need are already covered by tools that we already run
(detekt, Gradle, BCV). [ADR-008](../decisions/008-architecture-enforcement.md)
records the decision to remove the dangling Konsist references rather than
introduce a third enforcement engine for the same rules.

## Adding a New Invariant

1. Decide which tool category fits (structural → Gradle; idiom → detekt;
   surface → BCV; format → Spotless).
2. Implement the rule in the corresponding config file.
3. Add a row to the matrix above with a link to the source of truth and a
   link to the rule.
4. If the rule is non-obvious, write or extend an ADR explaining the *why*
   and reference it from the rule's `reason` field (detekt) or task
   description (Gradle).
5. Run `./gradlew check` to confirm the rule passes on the current tree, and
   ideally write a negative test demonstrating that it fails when violated.
