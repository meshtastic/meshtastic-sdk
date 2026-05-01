# ADR-008: Architecture enforcement via detekt + Gradle, not Konsist

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-04-19 |
| **Deciders** | maintainers |
| **Supersedes** | (none) |
| **Related** | [ADR-002](./002-architecture.md), [ADR-005](./005-api-shape.md), [ADR-006](./006-multi-module-rationale.md), [`docs/architecture/module-graph.md`](../architecture/module-graph.md) |

## Context

The audit flagged that AGENTS.md, CONTRIBUTING.md, and the ADR template all
reference `./gradlew :core:konsistTest` and "Konsist rule" follow-ups, but
there is no Konsist test code anywhere in the repository — only a dangling
version-catalog entry. The architectural invariants we actually need to
enforce are:

1. `:core` depends only on `:proto`. No transport, no storage.
2. No `kotlin.Result<T>` in any public API (ADR-005).
3. No `Mutex` / `synchronized` / `kotlinx.atomicfu.*` import inside
   `org.meshtastic.sdk.internal.engine.*` packages — the engine is the
   single-writer actor (ADR-002). The two existing `atomicfu` sites are
   *boundaries* into the actor and live outside the engine package; they
   are documented inline.
4. No `java.*` / `android.*` types in `commonMain`.
5. Public API surface is stable across changes (golden-file diff).

Today, (5) is fully covered by the Kotlin Gradle Plugin's built-in ABI
validation (`checkKotlinAbi` / `updateKotlinAbi`, Kotlin 2.2+; ADR-005,
applied in audit-track A5; migrated from kotlinx-binary-compatibility-validator
in 0.x). (1) is enforced *implicitly* because
`:core/build.gradle.kts` simply does not declare those dependencies — but
nothing actively detects accidental drift. (2)–(4) have no automation.

We need to pick a tool to close the gap. Three candidates were considered.

## Decision

Use **detekt with the `forbidden-import-rule`** (built-in) plus a small
Gradle verification task for the module-boundary check. **Drop Konsist
entirely** — remove the catalog entry and all references in agent docs.

Concretely:

- A repo-shared `config/detekt/detekt.yml` adds `ForbiddenImport` patterns
  scoped to `org.meshtastic.sdk.internal.engine.**` to ban
  `kotlinx.atomicfu.*`, `kotlinx.coroutines.sync.Mutex`, and
  `java.util.concurrent.locks.*`.
- A repo-shared `ForbiddenImport` rule in `commonMain` source sets bans
  `kotlin.Result` from public surface (BCV catches the leak too, but the
  detekt rule is the explicit hint for contributors).
- A `:core:verifyModuleBoundary` Gradle task fails if the resolved
  `commonMainImplementationDependencies` configuration contains anything
  other than `:proto` and the allowlisted external deps.

## Rationale

- **Already on detekt.** Detekt is wired across all subprojects and
  baselines exist; one more rule entry is a config edit, no new toolchain.
- **Konsist would duplicate BCV + Gradle.** Its sweet spot is "iterate over
  every class in source roots and assert structure"; we already get
  surface-level invariants from BCV (checkKotlinAbi) and dependency invariants
  from Gradle's own dep graph. Adding Konsist means a third source of truth
  for the same set of guarantees, and another JUnit suite to maintain.
- **Gradle for boundaries, detekt for code.** Module-dep rules belong with
  the resolver because that's where the answer is authoritative; per-file
  forbidden patterns belong with the static analyzer because they need
  source positions for error messages.

## Alternatives considered

| Option | Why not |
|---|---|
| **Konsist** | Duplicates BCV (surface) and Gradle (deps); pulls a separate test infrastructure for invariants we can already express with the tools we have. The existing dangling reference in our docs is the strongest argument *against* — we've spent two release cycles and haven't actually written one rule. |
| **detekt custom rules (separate jar)** | Overkill for a few `ForbiddenImport` patterns. The built-in rule is exactly what we need. We can revisit when we have a rule that genuinely requires the AST. |
| **Maintain status quo (manual review)** | The audit findings include exactly this category — drift between what docs promise and what the build enforces. Manual review is what got us here. |

## Consequences

### Positive

- One source of truth per category: BCV for surface, Gradle for deps,
  detekt for forbidden patterns.
- No new test runtime, no new Gradle task graph, no new agent skill needed.
- Removes a dangling promise (`konsistTest`) that has confused contributors.

### Negative / costs

- detekt's `ForbiddenImport` operates on string patterns; it can't reason
  about call graphs or type hierarchies. If we ever need to ban *uses* of a
  type beyond its import (rare in our codebase), we'll need to author a
  custom detekt rule or revisit Konsist.
- The `:core:verifyModuleBoundary` task is a small bespoke Gradle task we
  own. It's ~20 lines but it's still ours to maintain.

### Follow-ups

- [x] Remove `konsist` from `gradle/libs.versions.toml`.
- [x] Remove `./gradlew :core:konsistTest` references from `AGENTS.md`,
      `CONTRIBUTING.md`, and the ADR template.
- [x] Add the `ForbiddenImport` rules to `config/detekt/detekt.yml`
      (B2 in the audit-remediation plan).
- [x] Add `:core:verifyModuleBoundary` Gradle task and wire into `check`
      (B3).
- [ ] Document the enforcement matrix in `docs/architecture/enforcement.md`
      (B4).
- [x] Remove residual Konsist references from skill files, decision ADRs
      (003/005/006), `security.md`, `versioning.md`,
      `architecture/module-graph.md`, `README`, and `SPEC.md` (post-audit
      cleanup, 2026-04-19).

## References

- Detekt `ForbiddenImport` rule:
  <https://detekt.dev/docs/rules/style/#forbiddenimport>
- ADR-002 (single-writer actor invariant)
- ADR-005 (no `kotlin.Result` in public API)
