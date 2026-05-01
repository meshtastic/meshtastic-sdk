# Copilot Instructions for meshtastic-sdk

This Kotlin Multiplatform SDK requires careful navigation of cross-platform concurrency, wire-protocol semantics, and architectural boundaries. Follow these guidelines to work effectively.

## Prerequisites

**Environment setup is critical.** Before any work:
- JDK 21 (verify with `java -version`)
- Submodules initialized: `git clone --recurse-submodules` or `git submodule update --init --recursive`
  - If `proto/src/protobufs/` is empty or stale, protocol/generated code will be wrong
- Android SDK API 35 + `ANDROID_HOME` (for Android targets)
- Xcode 15+ (macOS/iOS targets)
- Gradle 8.4+ (bundled; no separate install needed)

## Build, Test, Lint

**Full gate (run before any PR):**

```bash
./gradlew spotlessApply check                    # format + unit tests + lint + architecture rules + ABI check
bash .github/tooling/check.sh                    # schema + markdown frontmatter validation
```

**Targeted workflows (faster iteration):**

```bash
./gradlew detekt spotlessCheck                   # lint only, no build
./gradlew jvmTest                                # JVM unit tests only
./gradlew iosSimulatorArm64Test                  # iOS unit tests (macOS only)
./gradlew :core:verifyModuleBoundary             # enforce architecture rules
./gradlew checkKotlinAbi                         # detect public API changes
```

**ABI baseline workflow (intentional public API changes only):**

```bash
./gradlew updateKotlinAbi                        # refresh api/*.api baseline files
git add api/                                     # commit the updated baselines
git commit -s -m "chore: ABI baseline refresh"
```

Omit this if your change is internal (not public API). `checkKotlinAbi` will fail with guidance if needed.

**Test coverage:**
- Unit tests in `src/{jvm,android,ios,common}Test/kotlin/`
- Platform-specific tests respect module boundaries; no transport/storage logic in `:core:test`
- `gradle spotlessApply` auto-fixes formatting; check results before committing

## Architecture & Module Boundaries

The SDK enforces hard architectural rules via detekt `ForbiddenImport` + `:core:verifyModuleBoundary` Gradle task.

**Dependency graph (strict):**
- `:core` → `:proto` only (no transports, no storage)
- `:transport-ble`, `:transport-tcp`, `:transport-serial` → `:core` + their own protocol impls
- `:storage-sqldelight` → `:core` (no transport coupling)
- Each transport/storage is independently pluggable

**Hard rules:**
- Do not add transport/storage implementation code to `:core`
- Do not add `kotlin.Result<T>` to public API (see ADR-005 for response-shape rules)
- Engine concurrency model is single-writer actor; no mutex/atomic/synchronized in hot paths (see ADR-002)
- Public API must follow shape policy: throw exceptions, AdminResult wrappers, or Flows — never Result

**Where to look:**
- Module graph: [`docs/architecture/module-graph.md`](docs/architecture/module-graph.md)
- Architecture decisions: [`docs/decisions/002-architecture.md`](docs/decisions/002-architecture.md)
- Enforcement matrix: [`docs/architecture/enforcement.md`](docs/architecture/enforcement.md)
- ADR index: [`docs/decisions/`](docs/decisions/)

## Multiplatform Concurrency & Dispatchers

**Dispatcher shadowing quirk (KMP gotcha):**
On Native (iOS), `Dispatchers.IO` from common code fails with "it is internal in kotlinx.coroutines.Dispatchers". The public extension property is shadowed by an internal member. Workaround:

```kotlin
// ✗ WRONG: will fail on Native
Dispatchers.IO

// ✓ RIGHT: use per-platform expect/actual
expect val defaultStorageDispatcher: CoroutineDispatcher

// Apple actual:
internal actual val defaultStorageDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(4, "sqldelight-storage")
```

See `storage-sqldelight/src/{jvm,android,apple}Main/.../StorageDispatcher.*.kt` for reference.

**Main-safety of suspend operations:**
All suspend operations in `:core` and storage are safe to call from UI/main thread (they dispatch off-thread). Verify MutableSharedFlow emissions in `MeshEngine`.

**Thread-local JDBC quirk:**
`JdbcSqliteDriver(url)` uses ThreadLocal connection pools. Post-constructor `driver.execute("PRAGMA …")` only affects the creating thread. Always apply PRAGMAs via JDBC URL properties or `AndroidSqliteDriver.Callback.onConfigure()`.

## Public API & Versioning

**Before committing public API changes:**
1. Read [`docs/decisions/005-api-shape.md`](docs/decisions/005-api-shape.md) — documents response-shape policy
2. Run `./gradlew checkKotlinAbi` — detects unintended ABI drift
3. If changes are intentional, run `./gradlew updateKotlinAbi` and commit `api/*.api` files
4. Verify no `kotlin.Result<T>` sneaked into public signatures

**Versioning:**
- Track [`docs/versioning.md`](docs/versioning.md) for SemVer + pre-1.0 compatibility guarantees
- Breaking changes to public API are allowed pre-1.0 but require `updateKotlinAbi`

## Wire Protocol & Storage

**Protocol behavior:**
- Handshake, NodeDB, ACK correlation, retries: [`docs/protocol.md`](docs/protocol.md)
- Error taxonomy + failure semantics: [`docs/error-taxonomy.md`](docs/error-taxonomy.md)
- Device firmware quirks: [`docs/references/meshtastic-firmware-behavior.md`](docs/references/meshtastic-firmware-behavior.md)

**Storage layer:**
- Schema: `storage-sqldelight/src/commonMain/kotlin/.../Schema.kt`
- Per-driver PRAGMA strategy: `storage-sqldelight/src/{jvm,android,apple}Main/.../SqlDelightStorageProvider.*.kt`
- Dispatcher-wrapped operations: `SqlDelightStorage` wraps all suspend methods in `withContext(dispatcher)`
- Transactional writes enforced; see `StorageDriverTest` for WAL + atomicity verification

**Transport new modules:**
Use [`docs/architecture/transport-module-authoring.md`](docs/architecture/transport-module-authoring.md) as a checklist. Follow the [`transport-ble`](transport-ble/) structure:
- Separate module under `transport-*/`
- Implement `Transport` interface (in `:core`)
- Add platform-specific test fixtures
- Document threading model + error modes

## Documentation & Commit Style

**When behavior changes, update docs:**
- `CHANGELOG.md`: `[Unreleased] → Changed/Fixed/Added`
- `docs/decisions/`: New ADR or "Updates" section if existing decision affected
- README.md: Only for install/quick-start changes
- `docs/architecture/`: Sync with module graph, threading, storage changes
- `docs/protocol.md`: Wire protocol behavior changes

**Commit hygiene:**
- Sign with `-s` (DCO): `git commit -s -m "…"`
- Include Co-authored-by trailer (auto-added by Copilot): `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`
- Keep commits minimal and scoped; one architectural concern per commit
- Format: `type(scope): message` (e.g., `fix(storage-sqldelight): …`, `feat(transport-ble): …`)

## Testing Conventions

- Tests use `suspend` functions with `runTest { }` (kotlinx-coroutines test)
- Storage tests verify transactional WAL behavior on each platform
- Protocol tests use fixture data from `docs/references/meshtastic-firmware-behavior.md`
- No mocking of transports in `:core:test`; use test doubles in module-specific tests

## Samples & CLI

The `samples/cli/` module is a standalone runnable example using the SDK with Clikt (command-line framework). Treat it as a reference app, not part of the library:

- Samples build alongside SDK but are not published to Maven Central
- CLI help text is the source of truth for command documentation; update both code + help when changing commands
- Proto serialization: the `:proto` module is generated by Square Wire (`com.squareup.wire`); use the generated `Message` types directly when constructing wire payloads
- Shell wrappers (if any) are documentation aids; prefer inline bash examples in README.md
- Run samples with `./gradlew :samples:cli:run --args="send text --transport=tcp:192.168.1.180 --message hello"`

## Decision Records & Key Readings

**Always consult these before major changes:**
1. [`docs/decisions/000-charter.md`](docs/decisions/000-charter.md) — what SDK is **not**
2. [`docs/decisions/002-architecture.md`](docs/decisions/002-architecture.md) — module separation + actor model
3. [`docs/decisions/005-api-shape.md`](docs/decisions/005-api-shape.md) — response-shape policy (throw/AdminResult/Flow)
4. [`docs/decisions/008-architecture-enforcement.md`](docs/decisions/008-architecture-enforcement.md) — detekt + Gradle rules
5. [`docs/decisions/012-transport-threading.md`](docs/decisions/012-transport-threading.md) — concurrency guarantees
6. [`docs/decisions/014-storage-pragmas.md`](docs/decisions/014-storage-pragmas.md) — per-driver PRAGMA strategy

## Common Pitfalls

1. **Forgetting submodule sync** → proto code generation fails silently; verify `proto/src/protobufs/` is populated
2. **Dispatcher hardcoding** → use `expect/actual` for `Dispatchers.IO` access; don't call from common code
3. **Skipping updateKotlinAbi** → CI will fail; run it whenever public API changes intentionally
4. **Adding to `:core` what belongs in transport** → violates ADR-002; module boundaries enforced at build time
5. **No DCO signoff** → GitHub DCO app blocks PR; use `git commit -s`
6. **PRAGMA application order** → JVM needs JDBC URL props, Android needs callback, Apple can post-execute once
7. **Result<T> in public API** → violates ADR-005; use sealed exceptions or AdminResult wrappers instead

---

**Version:** Updated 2026-04-21 after storage + dispatcher audit.
For questions, consult the decision records or ask in `/ask` mode without polluting session history.
