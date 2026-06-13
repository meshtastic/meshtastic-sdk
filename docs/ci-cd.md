# CI / CD

> **Workflow enablement status (pre-1.0):** The PR/CI gates
> (`ci.yml`, `tooling-check.yml`, `dependency-review.yml`, `docs.yml`)
> are **enabled** and run on every PR / push to `main`. The supply-chain
> and scheduled workflows (`release.yml`, `codeql.yml`, `scorecard.yml`)
> still ship with a `.yml.disabled` suffix and will be re-enabled before
> the public 1.0 push. Local equivalents in
> [`release-runbook.md`](release-runbook.md) and `./gradlew check` remain
> the source of truth.

> Tooling per [ADR-003](./decisions/003-tooling.md). Release policy per
> [`versioning.md`](./versioning.md). Release mechanics per
> [`release-runbook.md`](./release-runbook.md).

This document describes what CI **does today** and explicitly flags what's
on the roadmap. If you're looking for "how do I cut a release?" go to
[`release-runbook.md`](./release-runbook.md). If you're looking for "what
runs on every PR?" stay here.

## Workflow inventory (current state)

| File | Trigger | Purpose | Runner |
|---|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | `pull_request` to `main`, `push` to `main` | Build + test + lint + checkKotlinAbi + architecture rules across JVM, Android, iOS | `ubuntu-latest` for JVM/Android/api/arch jobs; `macos-latest` for iOS |
| [`tooling-check.yml`](../.github/workflows/tooling-check.yml) | Path-filtered on `.github/**` and `.githooks/**` | Runs `bash .github/tooling/check.sh` — agent-tooling guardrails (CODEOWNERS sync, AGENTS.md, hook policy, action version pinning, schema validation) | `ubuntu-latest` |
| [`codeql.yml`](../.github/workflows/codeql.yml.disabled) | `push`/`pull_request` to `main`; weekly Mon 06:00 UTC | CodeQL static analysis for `java-kotlin` and `actions` | `ubuntu-latest` |
| [`scorecard.yml`](../.github/workflows/scorecard.yml.disabled) | `push` to `main`; weekly Mon 06:00 UTC; `branch_protection_rule` | OpenSSF Scorecard supply-chain posture; uploads SARIF | `ubuntu-latest` |
| [`dependency-review.yml`](../.github/workflows/dependency-review.yml) | `pull_request` to `main` | Fails PRs introducing high-severity vulnerable dependencies | `ubuntu-latest` |
| [`docs.yml`](../.github/workflows/docs.yml) | `push`/`pull_request` to `main` | Builds aggregated Dokka HTML; **deploys to GitHub Pages on `push` to `main` only** (PRs build but do not deploy) | `ubuntu-latest` |

That's the entire inventory at MVP. Everything else listed here is roadmap.

## Trigger summary

Quick reference: which workflow fires when. The `.yml.disabled` entries
listed above (release/codeql/scorecard) only fire once their suffix is
removed; the rest are live today.

| Event | Workflows |
|---|---|
| `pull_request` → `main` | `ci.yml` (all six jobs), `dependency-review.yml`, `codeql.yml`, `docs.yml` (build only), `tooling-check.yml` (path-filtered on `.github/**`/`.githooks/**`) |
| `push` → `main` (post-merge) | `ci.yml`, `codeql.yml`, `scorecard.yml`, `docs.yml` (build + deploy to Pages), `tooling-check.yml` (path-filtered) |
| `push` of tag `vX.Y.Z` | none directly — release is `workflow_dispatch` (`release.yml`) after the tag is pushed; see [`release-runbook.md`](release-runbook.md) |
| `workflow_dispatch` | `release.yml` (manual: `gh workflow run release.yml -f version=X.Y.Z`) |
| `schedule` (Mon 06:00 UTC) | `codeql.yml`, `scorecard.yml` |
| `branch_protection_rule` | `scorecard.yml` |

## Gates and their local equivalents

Every gate that runs in CI has a local Gradle invocation. Run the same
command before opening a PR to avoid red-CI churn.

| Gate | CI job | Local command | Enforced by |
|---|---|---|---|
| Spotless (formatting) | `test-jvm` | `./gradlew spotlessCheck` (verify) / `./gradlew spotlessApply` (rewrite) | Spotless plugin |
| detekt (static analysis + `ForbiddenImport`) | `test-jvm`, `arch-consistency` | `./gradlew detekt` | detekt + ADR-008 ruleset |
| JVM unit tests | `test-jvm` (Java 17 + 21 matrix) | `./gradlew jvmTest` | `kotlin.test` |
| Android unit tests | `test-android` | `./gradlew :core:compileDebugKotlinAndroid :core:testDebugUnitTest` | `kotlin.test` (Robolectric-free) |
| iOS Sim tests | `test-ios` (`macos-latest`) | `./gradlew :core:iosSimulatorArm64Test` (mac only) | `kotlin.test` → XCTest |
| Public API check | `api-check` | `./gradlew checkKotlinAbi` | Kotlin 2.3 built-in BCV (see ADR-005) |
| Module boundary | `arch-consistency` | `./gradlew :core:verifyModuleBoundary` | custom Gradle task in [`core/build.gradle.kts`](../core/build.gradle.kts) |
| SQLDelight migration verify | `test-jvm` (transitive via `:storage-sqldelight:jvmTest`) | `./gradlew :storage-sqldelight:verifySqlDelightMigration` | `verifyMigrations = true` in [`storage-sqldelight/build.gradle.kts`](../storage-sqldelight/build.gradle.kts) |
| Full gate (umbrella) | `full-check` | `./gradlew check` | aggregates all of the above |
| Agent-tooling guardrails | `tooling-check.yml` | `bash .github/tooling/check.sh` | shell + actionlint + ajv |

After an intentional public API change, regenerate the dumps:
`./gradlew updateKotlinAbi` and commit the resulting `api/*.api` files
in the same PR.



Six jobs run in parallel on every PR and on every push to `main`. None
have `needs:` so contributors see the complete failure picture in one
turn:

| Job | Command | Purpose |
|---|---|---|
| `test-jvm` | `./gradlew jvmTest` then `./gradlew spotlessCheck detekt` | JVM unit tests + format + static analysis. Matrix `[17, 21]` to prove min-bytecode compatibility. |
| `test-android` | `./gradlew :core:compileDebugKotlinAndroid :core:testDebugUnitTest` | Android compile-check + unit tests |
| `test-ios` | `./gradlew :core:iosSimulatorArm64Test` | iOS Sim build + tests (`macos-latest`); caches `~/.konan` |
| `api-check` | `./gradlew checkKotlinAbi` | BCV — fails if `api/*.api` drifts from committed dumps |
| `arch-consistency` | `./gradlew :core:verifyModuleBoundary detekt` | ADR-008 enforcement: `:core` deps + ForbiddenImport rules |
| `full-check` | `./gradlew check` | Full gate (PR-gated) — runs every applicable task as a safety net |

Protobuf types resolve from the published `org.meshtastic:protobufs` Maven
artifact, so no submodule checkout is needed. All jobs use
`gradle/actions/setup-gradle` (build cache shared across jobs).

### Caching

- **Gradle**: `gradle/actions/setup-gradle` provides shared remote build
  cache + dependency cache across jobs.
- **Konan**: `actions/cache` keyed on `gradle/libs.versions.toml` + the
  Gradle wrapper properties (rebuilds when KGP / native target versions
  change).

### Required checks (branch protection on `main`)

The repo currently has minimal branch protection. Recommended
configuration when we enable strict protection:

- `test-jvm`
- `test-android`
- `test-ios`
- `api-check`
- `arch-consistency`
- DCO (per [ADR-004](./decisions/004-licensing.md), enforced via the
  GitHub DCO App at the org level — no workflow file needed)

## `tooling-check.yml` — agent-tooling guardrails

Runs `bash .github/tooling/check.sh` whenever anything under
`.github/` or `.githooks/` changes. Validates:

- Every action reference is SHA-pinned (no bare `@v4`); the bash check
  greps every `uses:` line and fails on anything that isn't 40 hex chars.
- Every workflow file is `actionlint`-clean.
- `CODEOWNERS`, `AGENTS.md`, and the agent-report JSON schema parse.
- Pre-commit hook contents match the policy documented in
  [`CONTRIBUTING.md`](../CONTRIBUTING.md).

This is intentionally a **separate workflow** — it has no Gradle
dependencies and runs in seconds, so it gives fast feedback on
plumbing-only PRs without blocking on the main test matrix.

## Local equivalents

Every CI step reproduces locally:

```bash
./gradlew check                          # everything: build + test + lint + checkKotlinAbi + arch rules
./gradlew jvmTest                        # JVM unit tests
./gradlew :core:compileIosSimulatorArm64Kotlin   # iOS compile-check (macOS only)
./gradlew checkKotlinAbi                       # BCV
./gradlew updateKotlinAbi                        # rewrite api/ files (commit when intentional)
./gradlew :core:verifyModuleBoundary     # ADR-008 module-boundary check
./gradlew detekt                         # ForbiddenImport + complexity + style
./gradlew spotlessCheck                  # formatting (verify-only)
./gradlew spotlessApply                  # formatting (rewrite)
bash .github/tooling/check.sh            # agent-tooling guardrails
./gradlew :samples:cli:installDist       # CLI binary at samples/cli/build/install/cli/bin/cli
```

[`CONTRIBUTING.md`](../CONTRIBUTING.md) surfaces this list for everyday use.

## Module → platform target matrix

Per-module Kotlin Multiplatform targets. The base set comes from the
`meshtastic.kmp.library` convention plugin
([`KmpLibraryConventionPlugin.kt`](../build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt)),
which configures **`jvm()`, `iosArm64()`, `iosX64()`, `iosSimulatorArm64()`**
and calls `applyDefaultHierarchyTemplate()`. The `meshtastic.android.library`
plugin layers an `androidTarget` (single-variant via
`com.android.kotlin.multiplatform.library`) on top.

| Module | JVM | Android | iOS¹ | Notes |
|---|:-:|:-:|:-:|---|
| `:core` | ✅ | ✅ | ✅ | Engine + interfaces; re-exports the `org.meshtastic:protobufs` types via `api`. ADR-008 enforces no in-tree project deps. |
| `:storage-sqldelight` | ✅ (Sqlite JDBC) | ✅ (Android driver) | ✅ (Native driver, `appleMain`) | Per-target driver factory; PRAGMA `journal_mode=WAL` + `synchronous=NORMAL` set in [`SqlDelightStorageProvider.apple.kt`](../storage-sqldelight/src/appleMain/kotlin/org/meshtastic/kmp/storage/sqldelight/SqlDelightStorageProvider.apple.kt) (matched by JVM/Android driver factories). |
| `:transport-tcp` | ✅ | ✅ | ✅ | Pure ktor-network. |
| `:transport-ble` | ✅ (macOS / Windows / Linux via Kable JVM) | ✅ | ✅ | Kable backends per platform. |
| `:transport-serial` | ✅ | ✅ | ❌ | jSerialComm; iOS targets are **not** declared. Shared via a custom `jvmAndroidMain` source-set group (see [`transport-serial/build.gradle.kts`](../transport-serial/build.gradle.kts)). |
| `:testing` | ✅ | ✅ | ✅ | Same hierarchy as `:core`. |
| `:bom` | (Java platform) | — | — | Maven BOM, no Kotlin sources. |

¹ "iOS" means the three KMP iOS targets configured by the convention
plugin: `iosArm64`, `iosX64`, `iosSimulatorArm64`. We do **not** ship
`watchosX`, `tvosX`, `macosX`, `linuxX`, `mingwX`, `js`, or
`wasmJs` targets. Adding one requires updating
`KmpLibraryConventionPlugin` and is a SemVer minor (new artifact).

### Hierarchy template

We use Kotlin's `applyDefaultHierarchyTemplate()` everywhere — no
manual `iosMain { dependsOn(commonMain) }` plumbing. The default
template gives us, per module:

```
commonMain
└── nativeMain
    └── appleMain
        └── iosMain
            ├── iosArm64Main
            ├── iosX64Main
            └── iosSimulatorArm64Main
└── jvmMain
└── androidMain   (when meshtastic.android.library is applied)
```

`:transport-serial` extends this with an extra `jvmAndroid` group
(`jvmMain` + `androidMain` share `jSerialComm`-based code) — see its
build script for the `applyDefaultHierarchyTemplate { common { group("jvmAndroid") { … } } }`
override.

`:storage-sqldelight` uses `appleMain` (not `iosMain`) for its
`NativeSqliteDriver` factory so the same code would compile if we
later added `macosArm64`/`macosX64`.



`.githooks/pre-commit` (opt-in via `git config core.hooksPath .githooks`)
runs **only** `bash .github/tooling/check.sh`. It does not run formatters
or tests — those would be too slow for every commit. Run
`./gradlew spotlessApply` and `./gradlew check` manually before pushing
or rely on CI to catch drift.

## DCO

The org-level [GitHub DCO App](https://github.com/apps/dco) posts a check
status on every PR; no workflow file is needed. Authors who forget the
sign-off see a check failure with a fix-up command in the bot's comment.
See [ADR-004](./decisions/004-licensing.md).

## Release publishing

## `release.yml` — manual publish (Sonatype Central)

Triggered via `gh workflow run release.yml -f version=X.Y.Z` (or the GitHub
Actions UI). Workflow is `workflow_dispatch`-only and:

1. Checks out `refs/tags/v${version}` (must be pushed first).
2. Verifies `./gradlew currentVersion` matches the input.
3. `assemble`, then `check`, then `publishAndReleaseToMavenCentral` via the
   vanniktech plugin.

A `dry_run: true` input skips the publish step but still runs the build +
gate; useful for verifying the pipeline before a real release.

Required repository secrets (vanniktech-standard names):

- `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — Central Portal token.
- `SIGNING_IN_MEMORY_KEY` — ASCII-armored GPG private key.
- `SIGNING_IN_MEMORY_KEY_PASSWORD` — passphrase for the above.

See [`release-runbook.md`](./release-runbook.md) for the end-to-end
procedure.

## Renovate

Dependency updates (Gradle — including **`org.meshtastic:protobufs`** — and
GitHub Actions) are managed by Renovate; config at
[`../renovate.json`](../renovate.json). The `gradle` manager opens PRs for new
`org.meshtastic:protobufs` versions — review the API diff carefully because new
`oneof` arms break consumer exhaustive `when` and constitute a MINOR bump per
[`versioning.md`](./versioning.md).

## Roadmap

These are documented intentions, not current state. Each will get its
own workflow file when implemented:

- **`release.yml`** — implemented (see above).
- **`docs.yml`** — implemented (see inventory). Publishes Dokka HTML
  to GitHub Pages on `push` to `main`. Site URL:
  `https://meshtastic.github.io/meshtastic-sdk/`.
- **`hw-loop.yml`** — nightly conformance suite against a self-hosted
  runner with real radios. Post-1.0 only.
- **Snapshot publishing on `main`** — currently the runbook is manual;
  a `publish-snapshot` job in `ci.yml` would automate it once we wire
  the secrets.

## Related

- [ADR-003](./decisions/003-tooling.md) — tooling rationale.
- [ADR-004](./decisions/004-licensing.md) — DCO requirement.
- [ADR-007](./decisions/007-ios-distribution.md) — KMMBridge for iOS
  distribution.
- [ADR-008](./decisions/008-architecture-enforcement.md) — what the
  `arch-consistency` job enforces.
- [`release-runbook.md`](./release-runbook.md) — how to actually cut a
  release today.
- [`versioning.md`](./versioning.md) — SemVer policy enforced by
  `checkKotlinAbi` + the manual release workflow.
- [`manual-tests.md`](./manual-tests.md) — what CI cannot test (real
  hardware paths).
- [`../renovate.json`](../renovate.json) — dependency updater config
  (Gradle artifacts incl. `org.meshtastic:protobufs`, GitHub Actions).
