# AGENTS.md

Guidance for AI coding agents working in this repository.

## First read

1. `README.md`
2. `.specify/memory/constitution.md`
3. `docs/protocol.md`
4. `docs/architecture/module-graph.md`
5. `docs/architecture/engine-actor.md`
6. `docs/decisions/002-architecture.md`
7. `docs/decisions/005-api-shape.md`
8. `docs/error-taxonomy.md`
9. `CONTRIBUTING.md`

These are the source of truth, and the constitution outranks the rest. Link to them from PR descriptions and generated docs rather than restating them.

## Environment

- JDK 21.
- Android SDK at the `androidCompileSdk` level in `gradle/libs.versions.toml`, with `ANDROID_HOME` set.
- Xcode on macOS for the iOS targets.
- Node.js on `PATH` for the `js` and `wasmJs` targets (`:core`, `:testing`, `:transport-websocket`); the Kotlin plugin is configured not to download one.
- No submodules. Protobuf types come from the published `org.meshtastic:protobufs` artifact, pinned as `meshtasticProtobufs` in `gradle/libs.versions.toml`.

If generated protocol symbols look wrong, check that the pin resolves: `./gradlew :core:dependencies --configuration jvmCompileClasspath | grep protobufs`.

## Commands

- Full gate: `./gradlew check`
- Unit tests: `./gradlew jvmTest`; `./gradlew jsNodeTest wasmJsNodeTest` (Node.js on `PATH`); `./gradlew jsBrowserTest` (Chrome, or `CHROME_BIN`); on macOS when relevant, `./gradlew iosSimulatorArm64Test`
- Lint: `./gradlew detekt spotlessCheck`
- Format: `./gradlew spotlessApply`
- Architecture rules: detekt `ForbiddenImport` and the `:core:verifyModuleBoundary` task, both wired into `check`. See [ADR-008](docs/decisions/008-architecture-enforcement.md) and [`docs/architecture/enforcement.md`](docs/architecture/enforcement.md).
- API compatibility: `./gradlew checkKotlinAbi`
- API dump refresh, only when public API changes on purpose: `./gradlew updateKotlinAbi`
- Agent tooling and changelog fragment checks: `bash .github/tooling/check.sh`

Use targeted tasks while iterating, then run `./gradlew check` before finishing.

## Architecture rules

- Respect the module boundaries in `docs/architecture/module-graph.md`.
- `:core` declares no in-tree project dependency; its only proto dependency is the published `org.meshtastic:protobufs` artifact, re-exported through `api`.
- Don't add transport or storage implementation dependencies to `:core`.
- The engine is a single-writer actor; don't add mutex, atomic or synchronized patterns on engine paths (ADR-002).
- No `java.*` or `android.*` imports in `commonMain`. Use `okio.ByteString` for byte payloads (Wire's runtime type; kotlinx-io is deliberately not a dependency) and `kotlinx-datetime` for time.
- Transport-side locks and atomics (lifecycle and handle ownership only) are allowed but must carry an inline `// ADR-012: native-handle ownership` or `// ADR-012: lifecycle idempotency` comment; see `docs/decisions/012-transport-threading.md`.

## Public API rules

- Follow the API shape rules in ADR-005. No `kotlin.Result<T>` in public API.
- An intentional public API change commits the regenerated `api/` dumps from `updateKotlinAbi`.
- Every public symbol needs KDoc. Review enforces it; no build task does. `./gradlew dokkaGenerate` builds the docs (Dokka V2 has no `dokkaHtml` task).

## Workflow

- Keep changes minimal and scoped.
- Add or update tests for behavior changes.
- Record a changelog entry as a `changelog.d/<name>.<section>.md` fragment (format in `changelog.d/README.md`); never edit `CHANGELOG.md` in a PR. A breaking change adds a `*.breaking.md` fragment.
- Update `docs/` when behavior, protocol handling or contributor workflow changes.
- Sign commits with DCO (`git commit -s`).

## Where to look by task

- Wire protocol behavior: `docs/protocol.md`
- Error mapping and failure semantics: `docs/error-taxonomy.md`
- CI jobs and local equivalents: `docs/ci-cd.md`
- Versioning, SemVer and API policy: `docs/versioning.md`
- Cutting a release (changelog cut, `release.yml` gates, Maven Central): `RELEASING.md`
- Manual device scenarios: `docs/manual-tests.md`
- Test tiers and conventions: `docs/testing.md`
- Logging (`LogSink`, `Builder.protocolLogging` opt-in): `docs/decisions/011-logsink.md`
- Transport threading contract (blocking I/O, Flow bridging, lock carve-outs): `docs/decisions/012-transport-threading.md`
- CLI JSON output (NDJSON envelope for scripting): `docs/decisions/013-proto-json-envelope.md`
- Storage PRAGMA strategy per driver: `docs/decisions/014-storage-pragmas.md`
- Web targets (`js`, `wasmJs`): `docs/decisions/017-web-targets.md`
- Test doubles: `FakeRadioTransport` and `TestClock` in `:testing`; `InMemoryStorage` in `:core`

## Agent tooling in the repo

- `AGENTS.md` (this file): guardrails and the routing table that follows.
- `CLAUDE.md`: a pointer to this file for runners that read the Claude filename.
- `GEMINI.md` and `.github/copilot-instructions.md`: pointers to this file for Gemini and Copilot.
- `.specify/memory/constitution.md`: the project's non-negotiable principles and their amendment rules.
- Skills (`.github/skills/`): workflow recipes, one `SKILL.md` per pack; `.github/skills/README.md` is the index.
- Agents (`.github/agents/`): `spec-guard.agent.md` is the spec-compliance review. `.claude/agents/spec-guard.md` is the Claude Code copy of spec-guard, since Claude Code loads only `.claude/agents/`. The two bodies are identical, so change both together.
- Prompts (`.github/prompts/`): `pre-pr-sanity.prompt.md` is the final pre-PR sweep.
- Eval harness (`.github/evals/`): a scoring smoke check for prompts.
- Tooling guardrails (`.github/tooling/`): `check.sh` validates JSON and frontmatter, lints shell scripts, workflows and markdown, requires SHA-pinned actions, and checks changelog fragments. `agent-report.schema.json` and `agent-report.template.json` define the structured agent report.

### Task routing

| Task | Use |
|---|---|
| Public API change (new types, signature edits, ADR-005 shape work) | skill `api-compat-guardian` |
| Wire framing, handshake, transport semantics, protocol mapping | skill `protocol-change-checklist` |
| New transport module or a non-trivial transport change | skill `transport-module-authoring` |
| Behavior, protocol, architecture or API changed and docs must follow | skill `docs-sync-guard` |
| Local or CI gate failed; classify and fix minimally | skill `ci-failure-triage` |
| Pre-release readiness (gates, SemVer, ADRs, `RELEASING.md`) | skill `release-readiness` |
| Cross-cutting spec-compliance review | agent `spec-guard` |
| Final pre-PR quality sweep | prompt `pre-pr-sanity` |

## Common pitfalls

- The `org.meshtastic:protobufs` pin in `gradle/libs.versions.toml` is the proto contract; a stale pin or a moving `-SNAPSHOT` is the usual cause of missing or renamed generated symbols.
- An intentional public API edit without `updateKotlinAbi` fails `checkKotlinAbi` in CI.
- Cross-platform changes must respect the target matrix in `docs/architecture/module-graph.md`.
- `Dispatchers.IO` from `commonMain` fails on Native ("it is internal"); use per-platform `expect`/`actual` dispatchers (see `storage-sqldelight/src/{jvm,android,apple}Main/.../StorageDispatcher.*.kt`).
- Wire-generated proto fields are `snake_case` (`user.long_name`); don't rename them.
- A transport-side `Mutex` or atomic without an `// ADR-012:` comment reads in review as an ADR-002 violation.
