# CI / CD

> Tooling per [ADR-003](./decisions/003-tooling.md). Release policy per
> [`versioning.md`](./versioning.md). Release mechanics per
> [`RELEASING.md`](../RELEASING.md).

This document describes what CI does and flags what's on the roadmap. For
"how do I cut a release?" go to [`RELEASING.md`](../RELEASING.md). For
"what runs on every PR?" stay here.

## Workflow inventory (current state)

| File | Trigger | Purpose | Runner |
|---|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | `pull_request` to `main`, `push` to `main`, `merge_group` | Build + test + lint + checkKotlinAbi + architecture rules across JVM, Android, iOS; coverage; snapshot publish on `push` to `main` | `ubuntu-latest`; `macos-latest` for iOS |
| [`release.yml`](../.github/workflows/release.yml) | `push` of tag `vX.Y.Z`; `workflow_dispatch` | Gates, signs and publishes a release to Maven Central and creates the GitHub Release ([`RELEASING.md`](../RELEASING.md)) | `ubuntu-latest` |
| [`tooling-check.yml`](../.github/workflows/tooling-check.yml) | Path-filtered on `.github/**` and `.githooks/**` | Runs `bash .github/tooling/check.sh` — agent-tooling guardrails (CODEOWNERS sync, AGENTS.md, hook policy, action version pinning, schema validation) | `ubuntu-latest` |
| [`codeql.yml`](../.github/workflows/codeql.yml) | `push`/`pull_request` to `main`; weekly Mon 06:00 UTC | CodeQL static analysis for `java-kotlin` and `actions` | `ubuntu-latest` |
| [`scorecard.yml`](../.github/workflows/scorecard.yml) | `push` to `main`; weekly Mon 06:00 UTC; `branch_protection_rule` | OpenSSF Scorecard supply-chain posture; uploads SARIF | `ubuntu-latest` |
| [`dependency-review.yml`](../.github/workflows/dependency-review.yml) | `pull_request` to `main` | Fails PRs introducing high-severity vulnerable dependencies | `ubuntu-latest` |
| [`docs.yml`](../.github/workflows/docs.yml) | `push`/`pull_request` to `main` | Builds aggregated Dokka HTML; **deploys to GitHub Pages on `push` to `main` only** (PRs build but do not deploy) | `ubuntu-latest` |

That's the entire inventory at MVP. Everything else listed here is roadmap.

## Trigger summary

Quick reference: which workflow fires when.

| Event | Workflows |
|---|---|
| `pull_request` → `main` | `ci.yml` (seven jobs; `publish-snapshot` is skipped), `dependency-review.yml`, `codeql.yml`, `docs.yml` (build only), `tooling-check.yml` (path-filtered on `.github/**`/`.githooks/**`) |
| `push` → `main` (post-merge) | `ci.yml` (all eight jobs), `codeql.yml`, `scorecard.yml`, `docs.yml` (build + deploy to Pages), `tooling-check.yml` (path-filtered) |
| `push` of tag `vX.Y.Z` (stable only) | `release.yml` |
| `workflow_dispatch` | `release.yml` (`gh workflow run release.yml -f version=X.Y.Z [-f dry_run=true]`) |
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



Seven jobs run in parallel on every PR and on every push to `main`. None
have `needs:` so contributors see the complete failure picture in one
turn. On a push to `main` an eighth, `publish-snapshot`, runs after the
test, API, architecture and full-check jobs pass:

| Job | Command | Purpose |
|---|---|---|
| `test-jvm` | `./gradlew jvmTest` then `./gradlew spotlessCheck detekt` | JVM unit tests + format + static analysis. Matrix `[17, 21]` to prove min-bytecode compatibility. |
| `test-android` | `./gradlew :core:compileDebugKotlinAndroid :core:testDebugUnitTest` | Android compile-check + unit tests |
| `test-ios` | `./gradlew :core:iosSimulatorArm64Test` | iOS Sim build + tests (`macos-latest`); caches `~/.konan` |
| `api-check` | `./gradlew checkKotlinAbi` | BCV — fails if `api/*.api` drifts from committed dumps |
| `arch-consistency` | `./gradlew :core:verifyModuleBoundary detekt` | ADR-008 enforcement: `:core` deps + ForbiddenImport rules |
| `full-check` | `./gradlew check` | Full gate (PR-gated) — runs every applicable task as a safety net |
| `coverage` | Kover XML report | Uploads coverage to Codecov; the check is named `Coverage (Kover -> Codecov)` |
| `publish-snapshot` | `publishAllPublicationsToMavenCentralRepository -Prelease.forceSnapshot` | `push` to `main` only: publishes `X.Y.Z-SNAPSHOT` to the Central Portal snapshot repository |

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

The `main` ruleset requires eight checks: `test-jvm (17)`, `test-jvm (21)`,
`test-android`, `test-ios (iosSimulatorArm64)`, `api-check`,
`arch-consistency`, `full-check` and `Coverage (Kover -> Codecov)`. The
release workflow reads the same list and refuses a commit where any of
them did not pass. DCO is enforced separately by the GitHub DCO App
(see below).

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

## Release publishing (`release.yml`)

Runs on a pushed stable `vX.Y.Z` tag or on `workflow_dispatch` with
`version` (and optionally `dry_run`). It refuses a commit off `main`, a
version with no `CHANGELOG.md` section, and a commit where any required
check did not pass; builds, checks and stages signed artifacts; refuses a
`-SNAPSHOT` dependency; then pushes the tag, publishes with
`publishAndReleaseToMavenCentral` and creates the GitHub Release. The full
sequence and the secrets are in [`RELEASING.md`](../RELEASING.md).

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

- **`hw-loop.yml`** — nightly conformance suite against a self-hosted
  runner with real radios. Post-1.0 only.

## Related

- [ADR-003](./decisions/003-tooling.md) — tooling rationale.
- [ADR-004](./decisions/004-licensing.md) — DCO requirement.
- [ADR-007](./decisions/007-ios-distribution.md) — KMMBridge for iOS
  distribution.
- [ADR-008](./decisions/008-architecture-enforcement.md) — what the
  `arch-consistency` job enforces.
- [`RELEASING.md`](../RELEASING.md) says how to cut a release.
- [`versioning.md`](./versioning.md) is the SemVer policy that
  `checkKotlinAbi` and the release workflow enforce.
- [`manual-tests.md`](./manual-tests.md) — what CI cannot test (real
  hardware paths).
- [`../renovate.json`](../renovate.json) — dependency updater config
  (Gradle artifacts incl. `org.meshtastic:protobufs`, GitHub Actions).
