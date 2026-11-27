# CI and CD

What each GitHub Actions workflow runs, and the local command for each gate. Tooling rationale is in [ADR-003](./decisions/003-tooling.md), release policy in [`versioning.md`](./versioning.md), and release steps in [`RELEASING.md`](../RELEASING.md).

## Workflows

| File | Trigger | Purpose | Runner |
|---|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | `pull_request` to `main`, `push` to `main`, `merge_group` | Tests on JVM, Android, iOS, JS and Wasm; lint; `checkKotlinAbi`; architecture rules; coverage; snapshot publish on `push` to `main` | `ubuntu-latest`; `macos-latest` for iOS |
| [`release.yml`](../.github/workflows/release.yml) | `push` of a `vX.Y.Z` tag; `workflow_dispatch` | Gates, signs and publishes a release to Maven Central and creates the GitHub Release ([`RELEASING.md`](../RELEASING.md)) | `ubuntu-latest` |
| [`tooling-check.yml`](../.github/workflows/tooling-check.yml) | `pull_request` and `push` to `main` touching `.github/**`, `.githooks/**`, `changelog.d/**` or `scripts/changelog*.sh` | Runs `bash .github/tooling/check.sh` | `ubuntu-latest` |
| [`codeql.yml`](../.github/workflows/codeql.yml) | `push` and `pull_request` to `main`; weekly, Monday 06:00 UTC | CodeQL analysis for `java-kotlin` and `actions` | `ubuntu-latest` |
| [`scorecard.yml`](../.github/workflows/scorecard.yml) | `push` to `main`; weekly, Monday 06:00 UTC; `branch_protection_rule` | OpenSSF Scorecard supply-chain report, uploaded as SARIF | `ubuntu-latest` |
| [`dependency-review.yml`](../.github/workflows/dependency-review.yml) | `pull_request` to `main` | Fails a PR that adds a dependency with a high-severity vulnerability | `ubuntu-latest` |
| [`docs.yml`](../.github/workflows/docs.yml) | `push` and `pull_request` to `main` | Builds the aggregated Dokka HTML; deploys it to GitHub Pages on `push` to `main` only | `ubuntu-latest` |

## Trigger summary

| Event | Workflows |
|---|---|
| `pull_request` to `main` | `ci.yml` (eight jobs; `publish-snapshot` is skipped), `dependency-review.yml`, `codeql.yml`, `docs.yml` (build only), `tooling-check.yml` (path-filtered) |
| `merge_group` | `ci.yml` (eight jobs) |
| `push` to `main` | `ci.yml` (nine jobs), `codeql.yml`, `scorecard.yml`, `docs.yml` (build and deploy), `tooling-check.yml` (path-filtered) |
| `push` of a `vX.Y.Z` tag | `release.yml` |
| `workflow_dispatch` | `release.yml` (`gh workflow run release.yml -f version=X.Y.Z [-f dry_run=true]`) |
| `schedule` (Monday 06:00 UTC) | `codeql.yml`, `scorecard.yml` |
| `branch_protection_rule` | `scorecard.yml` |

## `ci.yml` jobs

Eight jobs run in parallel on every PR, merge-queue entry and push to `main`. None uses `needs:`, so one run shows every failure. On a push to `main`, a ninth job, `publish-snapshot`, runs after `test-jvm`, `test-android`, `test-ios`, `api-check`, `arch-consistency` and `full-check` pass.

| Job | Command | Purpose |
|---|---|---|
| `test-jvm` | `./gradlew jvmTest`, then `./gradlew spotlessCheck detekt` | JVM tests, formatting and static analysis, on a JDK `[17, 21]` matrix to prove the JDK 17 bytecode runs |
| `test-android` | `./gradlew :core:testAndroidHostTest :core:assembleAndroidMain` | Android host tests and assembly for `:core` |
| `test-ios` | `./gradlew :core:iosSimulatorArm64Test`, then `:samples:parity-app:linkReleaseFrameworkIosSimulatorArm64` and `xcodebuild` on the SwiftUI shell | iOS simulator tests and the SKIE export check (`macos-latest`, caches `~/.konan`) |
| `test-js` | `./gradlew jsNodeTest wasmJsNodeTest` | Kotlin/JS and Kotlin/Wasm builds and `commonTest` under Node.js (`actions/setup-node`) |
| `api-check` | `./gradlew checkKotlinAbi` | Fails if the public surface differs from the committed `api/` dumps |
| `arch-consistency` | `./gradlew :core:verifyModuleBoundary detekt` | ADR-008: `:core` dependencies and `ForbiddenImport` rules |
| `full-check` | `./gradlew check` | The full gate, as a safety net |
| `coverage` | `./gradlew koverXmlReport` | Uploads Kover coverage to Codecov; the check is named `Coverage (Kover -> Codecov)` |
| `publish-snapshot` | `./gradlew -Prelease.forceSnapshot publishAllPublicationsToMavenCentralRepository` | `push` to `main` only: publishes `X.Y.Z-SNAPSHOT` to the Central Portal snapshot repository |

Protobuf types resolve from the published `org.meshtastic:protobufs` artifact, so no job needs a submodule.

### Caching

- Gradle: every job goes through the `.github/actions/gradle-setup` composite action, which validates the wrapper, sets up the JDK and runs `gradle/actions/setup-gradle` with an encrypted cache. Only runs on `main` write to the cache; other branches read it.
- Konan: `actions/cache` keyed on `gradle/libs.versions.toml` and the Gradle wrapper properties, so it rebuilds when the Kotlin or native toolchain changes.

### Required checks

The `main` ruleset requires nine checks: `test-jvm (17)`, `test-jvm (21)`, `test-android`, `test-ios (iosSimulatorArm64)`, `test-js`, `api-check`, `arch-consistency`, `full-check` and `Coverage (Kover -> Codecov)`. The release workflow reads the same list and refuses a commit where any of them didn't pass. The GitHub DCO App enforces sign-off separately.

## Gates and local equivalents

| Gate | CI job | Local command | Enforced by |
|---|---|---|---|
| Spotless (formatting) | `test-jvm` | `./gradlew spotlessCheck`, or `./gradlew spotlessApply` to rewrite | Spotless |
| detekt (static analysis, `ForbiddenImport`) | `test-jvm`, `arch-consistency` | `./gradlew detekt` | detekt with the ADR-008 rules, over every source set under each module's `src/` and against its `detekt-baseline.xml` |
| JVM tests | `test-jvm` | `./gradlew jvmTest` | `kotlin.test` |
| Android host tests | `test-android` | `./gradlew :core:testAndroidHostTest` | `kotlin.test` |
| iOS simulator tests | `test-ios` | `./gradlew :core:iosSimulatorArm64Test` (macOS) | `kotlin.test` on XCTest |
| JS and Wasm tests | `test-js` | `./gradlew jsNodeTest wasmJsNodeTest` (`node` on `PATH`) | `kotlin.test` on Node.js |
| Public API check | `api-check` | `./gradlew checkKotlinAbi` | the Kotlin Gradle plugin's ABI validation |
| Module boundary | `arch-consistency` | `./gradlew :core:verifyModuleBoundary` | a Gradle task in [`core/build.gradle.kts`](../core/build.gradle.kts) |
| SQLDelight migrations | `full-check` | `./gradlew :storage-sqldelight:verifySqlDelightMigration` | `verifyMigrations = true` in [`storage-sqldelight/build.gradle.kts`](../storage-sqldelight/build.gradle.kts) |
| Full gate | `full-check` | `./gradlew check` | every Gradle gate in this table |
| Agent tooling and changelog fragments | `tooling-check.yml` | `bash .github/tooling/check.sh` | shellcheck, actionlint, markdownlint, ajv |

After an intentional public API change, run `./gradlew updateKotlinAbi` and commit the `api/` dumps in the same PR.

## `tooling-check.yml`

Runs `bash .github/tooling/check.sh` when anything under `.github/`, `.githooks/` or `changelog.d/`, or `scripts/changelog*.sh`, changes. The script:

- Parses every JSON file under `.github/` and checks prompt and agent frontmatter.
- Validates the agent report template against its schema.
- Runs shellcheck on the tooling and changelog scripts, then `scripts/changelog_test.sh` and `scripts/changelog.sh check`, which rejects a malformed `changelog.d/` fragment.
- Runs markdownlint on `.github/**/*.md` and actionlint on the workflows.
- Requires every action reference to be pinned to a 40-character commit SHA.
- Runs the eval scorer smoke check.

It is a separate workflow because it needs no Gradle and finishes in seconds, so plumbing-only PRs get feedback without waiting for the test matrix.

## Local equivalents

```shell
./gradlew check                                # build, tests, lint, checkKotlinAbi, architecture rules
./gradlew jvmTest                              # JVM tests
./gradlew jsNodeTest wasmJsNodeTest            # JS and Wasm tests (Node.js on PATH)
./gradlew :core:iosSimulatorArm64Test          # iOS tests (macOS)
./gradlew checkKotlinAbi                       # public API check
./gradlew updateKotlinAbi                      # rewrite the api/ dumps (commit when intended)
./gradlew :core:verifyModuleBoundary           # ADR-008 module boundary
./gradlew detekt                               # ForbiddenImport, complexity, style
./gradlew spotlessCheck                        # formatting, verify only
./gradlew spotlessApply                        # formatting, rewrite
bash .github/tooling/check.sh                  # agent tooling and changelog fragments
./gradlew :samples:cli:installDist             # CLI at samples/cli/build/install/cli/bin/cli
```

## Pre-commit hook

`.githooks/pre-commit`, enabled with `git config core.hooksPath .githooks`, runs only `bash .github/tooling/check.sh`. Formatters and tests are too slow for every commit, so run `./gradlew spotlessApply` and `./gradlew check` before pushing.

## Module targets

The `meshtastic.kmp.library` convention plugin ([`KmpLibraryConventionPlugin.kt`](../build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt)) configures `jvm()`, `iosArm64()`, `iosX64()` and `iosSimulatorArm64()` with `applyDefaultHierarchyTemplate()`. `meshtastic.android.library` adds the Android target (single variant, through `com.android.kotlin.multiplatform.library`). `meshtastic.js.library` ([`JsLibraryConventionPlugin.kt`](../build-logic/convention/src/main/kotlin/JsLibraryConventionPlugin.kt)) adds `js` and `wasmJs`, each with a Node.js test environment, on the modules that apply it ([ADR-017](./decisions/017-web-targets.md)).

| Module | JVM | Android | iOS¹ | JS and Wasm² | Notes |
|---|:-:|:-:|:-:|:-:|---|
| `:core` | ✅ | ✅ | ✅ | ✅ | Engine and interfaces; re-exports the `org.meshtastic:protobufs` types through `api`. ADR-008 forbids in-tree project dependencies. |
| `:storage-sqldelight` | ✅ JDBC | ✅ Android driver | ✅ native driver (`appleMain`) | ✅ JS (browser worker), ❌ Wasm | Driver factory per target; PRAGMAs per [ADR-014](./decisions/014-storage-pragmas.md), applied in each `SqlDelightStorageProvider.*.kt`. |
| `:transport-tcp` | ✅ | ✅ | ✅ | ❌ | Ktor network sockets, which have no JS artifact. |
| `:transport-ble` | ✅ Kable btleplug backend | ✅ | ✅ | ✅ Web Bluetooth | Kable backend per platform. |
| `:transport-serial` | ✅ | ✅ | common types only | ❌ | jSerialComm in a custom `jvmAndroidMain` group (see [`transport-serial/build.gradle.kts`](../transport-serial/build.gradle.kts)). The iOS klibs carry `SerialFrameAssembler`, `SerialPortInfo` and the vendor ID set, and no backend. |
| `:transport-websocket` | ✅ CIO | ✅ CIO | ✅ Darwin | ✅ Js engine | Ktor client; CIO shared through a `jvmAndroidMain` group. |
| `:testing` | ✅ | ✅ | ✅ | ✅ | Same targets as `:core`. |
| `:bom` | Java platform | — | — | — | Maven BOM with no Kotlin sources. |

¹ The three iOS targets: `iosArm64`, `iosX64` and `iosSimulatorArm64`. No watchOS, tvOS, macOS, Linux or Windows native target is built. Adding a target changes the convention plugin and is a SemVer minor change (a new artifact).

² `js` (IR) and `wasmJs` klibs, published as `sdk-<module>-js` and `sdk-<module>-wasm-js`, which Kotlin/JS and Kotlin/Wasm consumers compile into their own bundle. No `@JsExport` surface or JavaScript bundle is published.

### Hierarchy template

Every module uses Kotlin's `applyDefaultHierarchyTemplate()` rather than hand-written `dependsOn` wiring. The default template gives:

```text
commonMain
├── nativeMain
│   └── appleMain
│       └── iosMain
│           ├── iosArm64Main
│           ├── iosX64Main
│           └── iosSimulatorArm64Main
├── jvmMain
├── androidMain   (with meshtastic.android.library)
└── webMain       (with meshtastic.js.library)
    ├── jsMain
    └── wasmJsMain
```

`:transport-serial` and `:transport-websocket` add a `jvmAndroid` group (`jvmMain` and `androidMain` sharing jSerialComm or CIO code) through `applyDefaultHierarchyTemplate { common { group("jvmAndroid") { … } } }` in their build scripts.

`:storage-sqldelight` puts its `NativeSqliteDriver` factory in `appleMain` rather than `iosMain`, so the code would also compile for a macOS target.

## DCO

The org-level [GitHub DCO App](https://github.com/apps/dco) posts a check on every PR; no workflow file is involved. A missing sign-off fails the check, and the app's comment gives the fix. See [ADR-004](./decisions/004-licensing.md).

## Release publishing

`release.yml` runs on a pushed `vX.Y.Z` tag, or on `workflow_dispatch` with `version` and optionally `dry_run`. It refuses a commit off `main`, a version with no `CHANGELOG.md` section, and a commit where a required check didn't pass. It builds, checks and stages signed artifacts, refuses a `-SNAPSHOT` dependency, then pushes the tag, publishes with `publishAndReleaseToMavenCentral` and creates the GitHub Release. [`RELEASING.md`](../RELEASING.md) has the full sequence and the secrets.

## Changelog fragments

`check.sh` runs `scripts/changelog.sh check`, which rejects a malformed `changelog.d/` fragment, and `scripts/changelog_test.sh`, which tests the script. The release cut folds the fragments into `CHANGELOG.md`, so `release.yml` reads a plain section with `scripts/changelog.sh notes`.

## Renovate

Renovate manages Gradle and GitHub Actions dependency updates through the org preset `meshtastic/.github:kmp-library`; the local config is [`../renovate.json`](../renovate.json). Third-party Gradle bumps automerge after a soak, gated on the required `api-check`. `org.meshtastic:protobufs` bumps never automerge: review the API diff, since a new `oneof` arm breaks a consumer's exhaustive `when` and is a minor bump per [`versioning.md`](./versioning.md).

## Roadmap

`hw-loop.yml`, a nightly conformance run on a self-hosted runner with real nodes, is planned for after 1.0.

## Related

- [ADR-003](./decisions/003-tooling.md): tooling rationale.
- [ADR-004](./decisions/004-licensing.md): the DCO requirement.
- [ADR-008](./decisions/008-architecture-enforcement.md): what `arch-consistency` enforces.
- [`RELEASING.md`](../RELEASING.md): how to cut a release.
- [`versioning.md`](./versioning.md): the SemVer policy `checkKotlinAbi` and the release workflow enforce.
- [`manual-tests.md`](./manual-tests.md): hardware paths CI can't test.
