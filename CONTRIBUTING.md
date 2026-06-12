# Contributing to meshtastic-sdk

Welcome. This guide covers how to set up your environment, run the tests, and submit a contribution.

## Code of Conduct

This project follows the [Meshtastic Code of Conduct](https://meshtastic.org/docs/community/code-of-conduct/). Be excellent to one another.

## Developer Certificate of Origin (DCO)

We use the [Developer Certificate of Origin](https://developercertificate.org/), not a CLA. By signing off, you certify that you wrote the patch (or are authorized to submit it) and that you grant it under the project's license (GPL-3.0-only).

Sign every commit with `-s` / `--signoff`:

```bash
git commit -s -m "Your message"
```

This appends a trailer:

```
Signed-off-by: Your Name <your.email@example.com>
```

Configure once globally so you don't forget:

```bash
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"
git config --global format.signOff true
```

The org-wide [GitHub DCO App](https://github.com/apps/dco) blocks PRs whose
commits aren't signed off. To fix retroactively:

```bash
git rebase --signoff HEAD~N   # N = number of commits to amend
git push --force-with-lease
```

## Environment

| Tool | Version |
|---|---|
| JDK | 21 (Temurin recommended; install via `sdk install java 21-tem`) |
| Android Gradle Plugin (AGP) | **9.0+** (required for Kotlin 2.0 compatibility and KMP support; see **Build requirements** below) |
| Gradle | 8.4+ (bundled; see `gradle/wrapper/gradle-wrapper.properties`) |
| Android SDK | API 36 platform (only needed for Android targets); set `ANDROID_HOME` |
| Xcode | 15+ with iOS 14+ SDK (only needed for iOS targets, mac only) |
| Git | Any modern version with submodule support |

Clone with submodules:

```bash
git clone --recurse-submodules git@github.com:meshtastic/meshtastic-sdk.git
cd meshtastic-sdk
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

## Build requirements

### Android Gradle Plugin 9.0+

The project requires **AGP 9.0 or later** for:

- **Kotlin 2.0 compatibility** — the compiler version in `libs.versions.toml` is Kotlin 2.3.x, which requires AGP 9.0+.
- **Kotlin Multiplatform (KMP) support** — AGP 9.0+ is the first version with stable KMP support for Kotlin 2.0.

**AGP 8.x is not supported.** If your IDE or local environment has AGP 8.x, upgrade your Gradle plugin:

```kotlin
// build.gradle.kts (top-level or in a module)
plugins {
    id("com.android.library") version "9.1.1"  // or later
}
```

The version is pinned in `gradle/libs.versions.toml` under `agp = "9.1.1"` (or later). Verify your IDE has picked up the new version by running:

```bash
./gradlew --version   # Should show Gradle with AGP 9.x
```

### JDK 21 requirement

JDK 21 is the compile-time target. You can use any JDK 21+ toolchain (Temurin, Adoptium, etc.); IDEs typically auto-download it. If building from the CLI:

```bash
sdk install java 21-tem   # sdkman
# or
brew install openjdk@21   # macOS
```

### Protocol submodule

The `proto/` directory is a git submodule pointing to the canonical Meshtastic protobufs. If you see strange `MeshPacket`, `FromRadio`, etc. type resolution errors, verify the submodule state:

```bash
git submodule status
# Should show something like: " 1a2b3c4d5e6f proto/src/protobufs (v1.2.3)"

# If detached or missing:
git submodule update --init --recursive
```

## Build & test

| Task | Command |
|---|---|
| Full check | `./gradlew check` |
| JVM tests | `./gradlew jvmTest` |
| iOS sim tests (mac) | `./gradlew iosSimulatorArm64Test` |
| API surface check | `./gradlew checkKotlinAbi` |
| API surface dump (after intended change) | `./gradlew updateKotlinAbi` |
| Lint | `./gradlew detekt spotlessCheck` |
| Format | `./gradlew spotlessApply` |
| Architecture rules | `./gradlew detekt :core:verifyModuleBoundary` (matrix in [`docs/architecture/enforcement.md`](docs/architecture/enforcement.md), rationale in [ADR-008](docs/decisions/008-architecture-enforcement.md)) |
| Docs preview | `./gradlew dokkaHtmlMultiModule` (output: `build/dokka/htmlMultiModule`) |

Pre-commit hook (opt-in):

```bash
git config core.hooksPath .githooks
```

Runs `bash .github/tooling/check.sh` before each commit. That script
validates agent tooling guardrails (skill frontmatter, prompt schema,
etc.); it intentionally does not run formatters or tests so commits
stay fast. Run `./gradlew spotlessApply detekt` manually before
pushing.

## Public API changes (`checkKotlinAbi` vs `updateKotlinAbi`)

The `:core`, `:proto`, `:transport-*`, `:storage-sqldelight`, `:testing`,
and `:bom` modules are publishable artifacts; their public Kotlin
surface is captured in `<module>/api/<module>.klib.api` (and
`<module>/api/jvm/<module>.api` for JVM). The Kotlin Gradle Plugin's
built-in ABI validator (Kotlin 2.3+, wired by `PublishingConventionPlugin`)
hard-fails CI when the live surface drifts from the committed dump.

Two tasks, one workflow:

| Task | Read/write | When to run |
|---|---|---|
| `./gradlew checkKotlinAbi` | **read-only** — diffs the live surface against committed dumps and fails on any mismatch | Before every push (auto-bound to `check`). This is what the `api-check` CI job runs. |
| `./gradlew updateKotlinAbi` | **writes** — regenerates `<module>/api/*.api(.klib.api)` files | **Only when public API changed *intentionally*.** Commit the regenerated files in the same PR. |

Workflow:

1. Make your change.
2. Run `./gradlew checkKotlinAbi`. Red? Inspect the diff — either:
   - **Unintentional drift** (a `public` slipped onto an internal type, a
     KDoc-only function widened): revert the source-side change.
   - **Intentional API change**: run `./gradlew updateKotlinAbi`,
     `git add api/`, and commit the new dumps **in the same PR** as the
     source change. The PR description must call out what changed and
     the SemVer impact (per [`docs/versioning.md`](docs/versioning.md)).
3. Pre-1.0: any breaking change is allowed but requires the dump
   refresh + a CHANGELOG `### Breaking` entry. 1.0+: the ABI baseline
   is treated as the contract.

> **Never** edit `api/*.api` files by hand. They are generated artefacts.

## SQLDelight schema migrations

`:storage-sqldelight` uses SQLDelight 2.x with `verifyMigrations = true`
(see [`storage-sqldelight/build.gradle.kts`](storage-sqldelight/build.gradle.kts)).
Two checks run in CI:

- The current schema in `Mesh.sq` must match the committed
  `databases/<N>.db` snapshot for the latest version `N`.
- Every committed migration `migration_<from>__<to>.sqm` must produce
  the next snapshot when applied to the previous one.

When you change the `.sq` schema:

1. Bump the schema version (`sqldelight { databases { create("MeshDatabase") { schemaOutputDirectory.set(...) ; ... } } }` already pins the version implicitly via the snapshot directory; the next snapshot file name is `<N+1>.db`).
2. Add `migration_N__N+1.sqm` describing the SQL needed to migrate an
   on-disk v`N` database to v`N+1` (e.g., `DROP TABLE messages;` for
   the v1 → v2 migration that landed in the unreleased section). Use
   plain SQL — no SQLDelight DSL.
3. Regenerate the schema snapshot:
   ```bash
   ./gradlew :storage-sqldelight:generateCommonMainMeshDatabaseSchema
   ```
   Commit the new `databases/<N+1>.db` alongside the migration.
4. Verify locally:
   ```bash
   ./gradlew :storage-sqldelight:verifySqlDelightMigration
   ./gradlew :storage-sqldelight:check
   ```
5. Add a manual-test entry in [`docs/manual-tests.md`](docs/manual-tests.md)
   under section H if the migration touches user-visible data.
6. CHANGELOG `### Breaking` entry if existing on-disk databases will
   lose data; `### Changed` otherwise. Cross-reference
   [`docs/architecture/storage.md`](docs/architecture/storage.md).

In-memory databases (`:memory:` driver, used by tests and by
`SqlDelightStorageProvider(baseDir = "")`) skip migrations entirely —
they always start at the latest schema.



The engine architecture has hard rules enforced by detekt + Gradle (see [ADR-008](docs/decisions/008-architecture-enforcement.md)):

1. `:core` depends only on `:proto`. Never on a transport or storage implementation. (The `RadioTransport`, `StorageProvider`, and `DeviceStorage` interfaces live inside `:core` itself — see ADR-006.)
2. No `java.*`, `android.*`, `kotlin.Result<T>` in `commonMain` or public API.
3. No `Mutex` / `atomicfu` / `synchronized` in the engine package — the actor IS the synchronization primitive (see [ADR-002](docs/decisions/002-architecture.md)).
4. Public byte payloads use `okio.ByteString` (Wire's runtime type — already forced into the surface by the generated protos); `kotlinx-io` is deliberately not a dependency. See docs/api-reference.md § API conventions.

If you find these rules in your way, that's a design discussion — open an issue first; don't disable the rule.

## Submitting a change

1. **Fork and branch.** Branch off `main`. Use a short, descriptive branch name (`fix/handshake-timeout`, `feat/transport-bluetooth-classic`).
2. **Discuss first for non-trivial work.** Open an issue (or comment on an existing one) before sinking days of work into a large change. We'll happily collaborate on the design.
3. **Make the change.**
   - Add tests where there's behavior to verify. The `:testing` module has `InMemoryStorage` and `FakeRadioTransport` for engine-level tests.
   - Update `docs/` if the change touches the spec, API, or workflow.
   - Run `./gradlew check` and fix anything red.
   - If you changed the public API: run `./gradlew updateKotlinAbi` and commit the regenerated `api/*.api` files.
4. **Sign off every commit** (`git commit -s`).
5. **Open the PR.** Fill in the template — particularly the affirmations about license/DCO/proto compliance.
6. **Iterate on review.** We use squash-merge; intermediate commits don't need to be perfect, but each PR should land as one logically-coherent commit.

### Commit messages

Conventional Commits format encouraged but not enforced:

```
feat(transport-tcp): add IPv6 literal support
fix(engine): resolve handle to Failed(Disconnected) when supervisor cancels
docs(adr): add ADR-007 on plugin loader
```

The squash-merge commit subject is what shipped — keep it readable.

## ADRs (Architecture Decision Records)

Significant decisions land as ADRs in [`docs/decisions/`](docs/decisions/). Format: see existing ADRs for the template (Context / Decision / Alternatives / Consequences / Status).

When to write an ADR:

- A design choice that has multiple valid alternatives and the trade-off matters.
- A constraint we're locking in (tooling, license, API shape).
- Anything you'd want a future contributor to find when they ask "why did we do it this way?".

Number sequentially: `NNN-slug.md`. Once accepted, an ADR is immutable; supersede with a new ADR rather than editing.

## Touching the wire protocol

If your change involves wire-level behavior (handshake, framing, ACK semantics, channel encryption, …):

1. Verify against the firmware (`meshtastic/firmware`). Cite specific files/lines in the PR description.
2. Cross-validate against `Meshtastic-Android` and `Meshtastic-Apple` where they implement the same thing. Cite their source paths.
3. Update [`docs/protocol.md`](docs/protocol.md) — the wire spec is the source of truth.
4. Add a manual-test entry in [`docs/manual-tests.md`](docs/manual-tests.md) if the change can only be validated against a real device.

## Bumping the proto submodule

Routine bumps happen automatically via Renovate's `git-submodules` manager
(see `renovate.json`). Manually:

```bash
cd proto/src/protobufs
git fetch origin master
git checkout origin/master
cd ../../..
./gradlew updateKotlinAbi
git add proto/src/protobufs api/
git commit -s -m "chore(proto): bump submodule (<prev>..<new>)"
```

Review the generated API diff carefully — new `oneof` arms break exhaustive `when` for consumers. SemVer impact per [`docs/versioning.md`](docs/versioning.md).

## Reusing code from sibling Meshtastic-org projects

Per [ADR-004](docs/decisions/004-licensing.md), this SDK lives inside the `meshtastic` org and uses GPL-3.0, same as `Meshtastic-Android`/`Meshtastic-Apple`/`firmware`. Lifting code from those projects is allowed (and often the right answer for protocol-correct behavior). When you do:

1. Add a second copyright line in the file header crediting the source repo + author.
2. Note the origin in the commit message: `Origin: Meshtastic-Android/.../HeartbeatSender.kt @ <commit-sha>`.
3. Keep the SPDX `GPL-3.0-only` line.

Code from outside the org follows ordinary GPL-compatibility rules.

## Reporting bugs

Open an issue using one of the templates. Include:

- SDK version (Maven coordinates).
- Target platform (Android API X, iOS X, JVM X, wasm browser X).
- Transport in use.
- Device firmware version (`AdminApi.getMetadata()` or check the device's About screen).
- Minimal reproduction (CLI command if possible).
- Logs at `LogLevel.Debug` if applicable.

## Reporting security issues

Don't open a public issue. Follow the disclosure process in [`SECURITY.md`](SECURITY.md) — file a private GitHub Security Advisory on this repo, or use the contacts listed there. See [`docs/security.md`](docs/security.md) for what's in scope.

## Questions

Discord / Discourse links live in the org README. For SDK-specific questions, GitHub Discussions on this repo (once enabled).
