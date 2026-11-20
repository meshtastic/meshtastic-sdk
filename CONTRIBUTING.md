# Contributing to meshtastic-sdk

This guide covers setting up a build, running the checks and submitting a change.

## Code of conduct

The project follows the [Meshtastic Code of Conduct](https://meshtastic.org/docs/community/code-of-conduct/).

## Developer Certificate of Origin (DCO)

Meshtastic uses the [Developer Certificate of Origin](https://developercertificate.org/), not a CLA. Signing off certifies that you wrote the patch, or may submit it, under the project's license (GPL-3.0-or-later).

Sign every commit with `-s` (`--signoff`):

```shell
git commit -s -m "Your message"
```

That appends a trailer:

```text
Signed-off-by: Your Name <your.email@example.com>
```

To sign off by default:

```shell
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"
git config --global format.signOff true
```

The org-wide [GitHub DCO App](https://github.com/apps/dco) blocks a pull request with an unsigned commit. To sign off existing commits, where `N` is the number of commits to amend:

```shell
git rebase --signoff HEAD~N
git push --force-with-lease
```

## Environment

| Tool | Version |
|---|---|
| JDK | 21 (`sdk install java 21-tem`, or `brew install openjdk@21` on macOS) |
| Gradle | the wrapper's version (`gradle/wrapper/gradle-wrapper.properties`); no separate install |
| Android Gradle plugin, Kotlin | pinned in `gradle/libs.versions.toml` (`agp`, `kotlin`) |
| Android SDK | the `androidCompileSdk` platform from `gradle/libs.versions.toml`, with `ANDROID_HOME` set; only for Android targets |
| Node.js | on `PATH`, only for the `js` and `wasmJs` targets; Gradle doesn't download it |
| Xcode | on macOS, only for the iOS targets |

JDK 21 is the build toolchain. The published JVM and Android bytecode targets JDK 17 (`jvmTarget` in `gradle/libs.versions.toml`, compiled with `-Xjdk-release=17`), so code that calls a JDK 18 or later API fails to compile.

Clone:

```shell
git clone git@github.com:meshtastic/meshtastic-sdk.git
cd meshtastic-sdk
```

There are no submodules. Protobuf types come from the published `org.meshtastic:protobufs` artifact, pinned as `meshtasticProtobufs` in `gradle/libs.versions.toml`. If `MeshPacket`, `FromRadio` or other generated types fail to resolve, check that the pin resolves:

```shell
./gradlew :core:dependencies --configuration jvmCompileClasspath | grep protobufs
```

## Build and test

| Task | Command |
|---|---|
| Full check | `./gradlew check` |
| JVM tests | `./gradlew jvmTest` |
| iOS simulator tests (macOS) | `./gradlew iosSimulatorArm64Test` |
| JS and Wasm tests (`node` on `PATH`) | `./gradlew jsNodeTest wasmJsNodeTest` |
| API surface check | `./gradlew checkKotlinAbi` |
| API surface dump, after an intended change | `./gradlew updateKotlinAbi` |
| Lint | `./gradlew detekt spotlessCheck` |
| Format | `./gradlew spotlessApply` |
| Architecture rules | `./gradlew detekt :core:verifyModuleBoundary` (matrix in [`docs/architecture/enforcement.md`](docs/architecture/enforcement.md), rationale in [ADR-008](docs/decisions/008-architecture-enforcement.md)) |
| API docs | `./gradlew dokkaGenerate` (Dokka V2; HTML under `build/dokka/html`) |

An opt-in pre-commit hook runs `bash .github/tooling/check.sh`, which validates the agent tooling files and changelog fragments:

```shell
git config core.hooksPath .githooks
```

It doesn't run formatters or tests, so run `./gradlew spotlessApply detekt` before pushing.

## Public API changes

`:core`, the `:transport-*` modules, `:storage-sqldelight` and `:testing` are published, and their public Kotlin surface is captured in `<module>/api/<module>.klib.api` and `<module>/api/jvm/<module>.api`. The Kotlin Gradle plugin's ABI validation, wired by `PublishingConventionPlugin`, fails CI when the live surface differs from the committed dumps.

| Task | Effect | When to run |
|---|---|---|
| `./gradlew checkKotlinAbi` | Read-only: diffs the live surface against the committed dumps and fails on any mismatch | Before every push. `check` runs it, and so does the `api-check` CI job. |
| `./gradlew updateKotlinAbi` | Writes: regenerates the `api/` dumps | Only after an intentional public API change. Commit the result in the same PR. |

When `checkKotlinAbi` fails, read the diff:

- Unintended drift, such as a `public` that slipped onto an internal type: revert the source change.
- An intended change: run `./gradlew updateKotlinAbi` and commit the new dumps with the source change. The PR description names what changed and its SemVer impact ([`docs/versioning.md`](docs/versioning.md)).

A moved dump always needs a changelog fragment. If a consumer only has to recompile, it is `added`, `changed`, `deprecated` or `removed`; if they have to change code, it is `breaking`. Pre-1.0, a breaking change is allowed with the dump refresh and a `*.breaking.md` fragment. From 1.0 the dumps are the contract.

Never edit the `api/` dumps by hand.

## Changelog

[`CHANGELOG.md`](CHANGELOG.md) is hand-written in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form, and no entry is generated from commits. A PR never edits it. The entry is a fragment file, `changelog.d/<name>.<section>.md`, where `<name>` is the PR number or a short slug and `<section>` is `breaking`, `added`, `changed`, `deprecated`, `removed`, `fixed` or `security`. The file holds the `- ` bullets exactly as they will read in the changelog; [`changelog.d/README.md`](changelog.d/README.md) has the format.

`scripts/changelog.sh check` validates the fragments in CI, and `scripts/changelog.sh preview` shows what the next release folds in. The script folds, cuts and reads sections without re-rendering the file, so prose under any heading survives a release.

Add a fragment for anything a consumer would notice: a new or changed public API, a behavior change, a fix to something they could hit, a security property or a schema migration. Refactors, test-only changes, sample-only changes and CI work need none.

Three rules elsewhere are instances of this one: constitution principle V (a pre-1.0 breaking release must have a `### Breaking` section, which the cut writes from `*.breaking.md` fragments), [`GOVERNANCE.md`](GOVERNANCE.md) Decision making item 3 (every breaking change is called out in the changelog), and the migration rule in [SQLDelight schema migrations](#sqldelight-schema-migrations). `### Breaking` comes first in a version's section because, with committed ABI dumps, the first thing a consumer needs to know is whether recompiling is enough.

The changelog is also the GitHub Release text: `release.yml` puts `scripts/changelog.sh notes X.Y.Z` in the release body. A release PR runs `scripts/changelog.sh cut X.Y.Z`, which folds the fragments into `## [Unreleased]` before cutting; [RELEASING.md](RELEASING.md) has the steps.

## SQLDelight schema migrations

`:storage-sqldelight` uses SQLDelight 2 with `verifyMigrations = true` (see [`storage-sqldelight/build.gradle.kts`](storage-sqldelight/build.gradle.kts)). Migrations live in `storage-sqldelight/src/commonMain/sqldelight/databases/`, where `<n>.sqm` migrates schema version `n` to `n + 1` and `1.db` is the committed version 1 snapshot. Verification applies every migration to the snapshot and fails unless the result matches the schema in `Mesh.sq`.

To change the schema:

1. Edit `Mesh.sq`.
2. Add the next migration, `<n>.sqm`, where `n` is the schema version before your change (one more than the highest existing `.sqm`). Write plain SQL that migrates an on-disk database from version `n` to `n + 1`.
3. Verify:

   ```shell
   ./gradlew :storage-sqldelight:verifySqlDelightMigration
   ./gradlew :storage-sqldelight:check
   ```

4. Update the schema table in [`docs/architecture/storage.md`](docs/architecture/storage.md).
5. Add a manual test under section H of [`docs/manual-tests.md`](docs/manual-tests.md) if the migration touches user-visible data.
6. Add a `breaking` changelog fragment if existing on-disk databases lose data, and a `changed` one otherwise.

In-memory databases (`SqlDelightStorageProvider(baseDir = "")`, and the tests) skip migrations and always start at the latest schema.

## Architecture rules

detekt and Gradle enforce these ([ADR-008](docs/decisions/008-architecture-enforcement.md)):

1. `:core` declares no in-tree project dependency; its only proto dependency is the published `org.meshtastic:protobufs` artifact, re-exported through `api`. The `RadioTransport`, `StorageProvider` and `DeviceStorage` interfaces live in `:core` itself (ADR-006, ADR-015).
2. No `java.*` or `android.*` in `commonMain`, and no `kotlin.Result<T>` in public API.
3. No `Mutex`, `atomicfu` or `synchronized` in the engine package; the actor is the synchronization primitive ([ADR-002](docs/decisions/002-architecture.md)).
4. Public byte payloads use `okio.ByteString`, Wire's runtime type, which the generated protos already put in the surface. `kotlinx-io` is deliberately not a dependency. See `docs/api-reference.md` → API conventions.

If a rule is in your way, open an issue to discuss the design; don't disable the rule.

## Submitting a change

1. Fork and branch off `main`, with a short descriptive branch name (`fix/handshake-timeout`).
2. For non-trivial work, open an issue or comment on an existing one before investing days in it.
3. Make the change:
   - Add tests where there is behavior to verify. `FakeRadioTransport` in `:testing` and `InMemoryStorageProvider` in `:core` support engine-level tests.
   - Update `docs/` if the change touches the spec, API or workflow.
   - Run `./gradlew check` and fix anything red.
   - If the public API changed, run `./gradlew updateKotlinAbi` and commit the regenerated dumps.
4. Sign off every commit (`git commit -s`).
5. Open the PR and fill in the template, including the license, DCO and protocol affirmations.
6. Iterate on review. PRs are squash-merged, so intermediate commits don't need to be tidy, but each PR lands as one coherent commit.

### Commit messages

Conventional Commits are encouraged but not enforced:

```text
feat(transport-tcp): add IPv6 literal support
fix(engine): resolve handle to Failed(Disconnected) when supervisor cancels
docs(adr): add ADR-018 on plugin loading
```

The squash-merge subject is what ships, so keep it readable.

## ADRs (Architecture Decision Records)

Significant decisions are recorded as ADRs in [`docs/decisions/`](docs/decisions/), starting from [`_template.md`](docs/decisions/_template.md). Write one for:

- A design choice with several valid alternatives where the tradeoff matters.
- A constraint being locked in (tooling, license, API shape).
- Anything a future contributor would ask "why is it done this way?" about.

Number them sequentially as `NNN-slug.md`. An accepted ADR is immutable; a later ADR supersedes it.

## Touching the wire protocol

For a change to wire-level behavior (handshake, framing, ACK semantics, channel encryption):

1. Verify it against the firmware (`meshtastic/firmware`) and cite files and lines in the PR description.
2. Cross-check `Meshtastic-Android` and `Meshtastic-Apple` where they implement the same thing, and cite their source paths.
3. Update [`docs/protocol.md`](docs/protocol.md), the wire spec.
4. Add a manual test to [`docs/manual-tests.md`](docs/manual-tests.md) if only a real device can validate the change.

## Bumping the proto artifact

Renovate proposes new `org.meshtastic:protobufs` versions (`renovate.json`) and never automerges them. To bump by hand, set `meshtasticProtobufs` in `gradle/libs.versions.toml`, then:

```shell
./gradlew updateKotlinAbi
git add gradle/libs.versions.toml core/api/
git commit -s -m "chore(proto): bump org.meshtastic:protobufs to <NEW_VERSION>"
```

Review the regenerated ABI diff: a new `oneof` arm breaks a consumer's exhaustive `when`. The SemVer impact is in [`docs/versioning.md`](docs/versioning.md).

## Reusing code from Meshtastic projects

Under [ADR-004](docs/decisions/004-licensing.md) this SDK is GPL-3.0, like `Meshtastic-Android`, `Meshtastic-Apple` and `firmware`, so code from those projects can be reused, and often should be for protocol-correct behavior. When you do:

1. Add a second copyright line to the file header crediting the source repository and author.
2. Note the origin in the commit message: `Origin: Meshtastic-Android/.../HeartbeatSender.kt @ <COMMIT_SHA>`.
3. Keep the SPDX `GPL-3.0-or-later` line.

Code from outside the org follows the usual GPL compatibility rules.

## Reporting bugs

Open an issue from the bug template. It asks for:

- The SDK version (Maven coordinates).
- The target platform (Android API level, iOS version, JVM version, or browser for JS and Wasm).
- The transport.
- The node's firmware version (`AdminApi.getDeviceMetadata()`, or the device's About screen).
- A minimal reproduction, ideally a CLI command.
- Logs at `LogLevel.Debug` where relevant.

## Reporting security issues

Don't open a public issue. File a private GitHub Security Advisory as [`SECURITY.md`](SECURITY.md) describes. [`docs/security.md`](docs/security.md) defines the scope.

## Questions

General Meshtastic questions belong in the [community channels](https://meshtastic.org/docs/community/). This repository has GitHub Discussions turned off.
