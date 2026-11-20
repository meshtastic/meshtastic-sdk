# Copilot instructions for meshtastic-sdk

This Kotlin Multiplatform SDK has strict rules on concurrency, wire-protocol behavior and module boundaries. [`AGENTS.md`](../AGENTS.md) is the canonical agent guide; this file adds the traps Copilot sessions hit most.

## Prerequisites

- JDK 21 (`java -version`).
- The Android SDK platform named by `androidCompileSdk` in `gradle/libs.versions.toml`, with `ANDROID_HOME` set, for Android targets.
- Xcode on macOS for iOS targets.
- Node.js on `PATH` for the `js` and `wasmJs` targets.
- No submodules: protobuf types come from the published `org.meshtastic:protobufs` artifact, pinned as `meshtasticProtobufs` in `gradle/libs.versions.toml`. The Gradle wrapper supplies Gradle.

## Build, test, lint

Full gate, before any PR:

```shell
./gradlew spotlessApply check
bash .github/tooling/check.sh
```

`check` runs the tests, Spotless, detekt, the architecture rules and `checkKotlinAbi`. `check.sh` validates the agent tooling files, workflows and changelog fragments.

While iterating:

```shell
./gradlew detekt spotlessCheck
./gradlew jvmTest
./gradlew jsNodeTest wasmJsNodeTest
./gradlew iosSimulatorArm64Test
./gradlew :core:verifyModuleBoundary
./gradlew checkKotlinAbi
```

After an intentional public API change, run `./gradlew updateKotlinAbi` and commit the regenerated `api/` dumps with the change. An internal change needs no dump refresh.

Tests live in `src/commonTest` (plus `jvmTest`, `androidHostTest` and the iOS test source sets where a platform needs them). `:core` tests use `FakeRadioTransport` from `:testing`, never a mocked transport.

## Architecture and module boundaries

detekt `ForbiddenImport` and the `:core:verifyModuleBoundary` task enforce the module rules:

- `:core` depends on the published `org.meshtastic:protobufs` artifact and no in-tree module.
- `:transport-ble`, `:transport-tcp`, `:transport-serial` and `:transport-websocket` depend on `:core`.
- `:storage-sqldelight` depends on `:core`, with no transport coupling.
- Don't add transport or storage implementation code to `:core`.
- The engine is a single-writer actor: no mutex, atomic or synchronized on engine paths (ADR-002).
- Public API returns follow ADR-005: throw `MeshtasticException`, return a sealed outcome such as `AdminResult` or `SendOutcome`, or expose a `Flow`. Never `kotlin.Result<T>`.

References: [`docs/architecture/module-graph.md`](../docs/architecture/module-graph.md), [`docs/decisions/002-architecture.md`](../docs/decisions/002-architecture.md), [`docs/architecture/enforcement.md`](../docs/architecture/enforcement.md) and the [ADR index](../docs/decisions/).

## Multiplatform dispatchers

On Kotlin/Native, `Dispatchers.IO` from common code fails with "it is internal in kotlinx.coroutines.Dispatchers": an internal member shadows the public extension. Use a per-platform `expect`/`actual` dispatcher instead:

```kotlin
// commonMain
internal expect val defaultStorageDispatcher: CoroutineDispatcher

// appleMain
@OptIn(DelicateCoroutinesApi::class)
internal actual val defaultStorageDispatcher: CoroutineDispatcher =
    newFixedThreadPoolContext(STORAGE_PARALLELISM, "sqldelight-storage")
```

`storage-sqldelight/src/{jvm,android,apple}Main/.../StorageDispatcher.*.kt` has the full set.

Suspend functions in `:core` and the storage module are safe to call from the main thread; they dispatch off it themselves.

`JdbcSqliteDriver(url)` keeps a connection per thread, so a `driver.execute("PRAGMA ...")` after construction affects only the creating thread. Apply PRAGMAs through JDBC URL properties on the JVM and `AndroidSqliteDriver.Callback.onConfigure()` on Android; on Apple they run once after the driver opens ([ADR-014](../docs/decisions/014-storage-pragmas.md)).

## Wire protocol and storage

- Handshake, NodeDB, ACK correlation and retries: [`docs/protocol.md`](../docs/protocol.md).
- Error taxonomy: [`docs/error-taxonomy.md`](../docs/error-taxonomy.md).
- Firmware behavior notes: [`docs/references/meshtastic-firmware-behavior.md`](../docs/references/meshtastic-firmware-behavior.md).
- Storage schema: `storage-sqldelight/src/commonMain/sqldelight/.../Mesh.sq`, with migrations under `storage-sqldelight/src/commonMain/sqldelight/databases/`.
- `SqlDelightStorage` wraps every suspend method in `withContext(dispatcher)`; `StorageDriverTest` covers WAL and atomicity per driver.
- A new transport module follows the [`transport-module-authoring`](skills/transport-module-authoring/SKILL.md) skill: its own `transport-*` module implementing `RadioTransport` from `:core`, platform tests, and a `Module.md` covering threading and error modes.

## Docs and commits

When behavior changes:

- Add a changelog fragment, `changelog.d/<name>.<section>.md` (`breaking`, `added`, `changed`, `deprecated`, `removed`, `fixed`, `security`). Never edit `CHANGELOG.md` in a PR.
- Add an ADR under `docs/decisions/` for a new design decision; an accepted ADR is superseded by a new one, not edited.
- Update `README.md` for install or quick-start changes, `docs/architecture/` for module, threading or storage changes, and `docs/protocol.md` for wire behavior.

Commits are signed off for the DCO (`git commit -s`), scoped to one concern, and written as `type(scope): message`, such as `fix(storage-sqldelight): ...` or `feat(transport-ble): ...`.

## Samples and the CLI

`samples/cli/` is a runnable JVM reference app built on Clikt. It builds with the SDK and isn't published. Its `--help` text documents the commands, so update the help when a command changes. Build it with `./gradlew :samples:cli:installDist` and run `samples/cli/build/install/cli/bin/cli send text --transport=tcp:192.168.1.180 -m hello`.

## Key decision records

1. [`000-charter.md`](../docs/decisions/000-charter.md): what the SDK is not.
2. [`002-architecture.md`](../docs/decisions/002-architecture.md): module separation and the actor model.
3. [`005-api-shape.md`](../docs/decisions/005-api-shape.md): response shapes.
4. [`008-architecture-enforcement.md`](../docs/decisions/008-architecture-enforcement.md): detekt and Gradle rules.
5. [`012-transport-threading.md`](../docs/decisions/012-transport-threading.md): transport threading.
6. [`014-storage-pragmas.md`](../docs/decisions/014-storage-pragmas.md): PRAGMAs per driver.
7. [`017-web-targets.md`](../docs/decisions/017-web-targets.md): the `js` and `wasmJs` targets.

## Common pitfalls

1. A stale or moving `meshtasticProtobufs` pin causes missing or renamed generated symbols.
2. `Dispatchers.IO` in common code fails on Native; use `expect`/`actual`.
3. Skipping `updateKotlinAbi` after an intentional API change fails CI.
4. Transport code in `:core` violates ADR-002, and the build rejects it.
5. A commit without a DCO sign-off blocks the PR.
6. PRAGMA application differs per driver: JDBC URL properties on the JVM, the callback on Android, one execution after open on Apple.
7. `Result<T>` in public API violates ADR-005.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
