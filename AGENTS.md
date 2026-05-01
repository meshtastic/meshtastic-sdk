# AGENTS.md

Guidance for AI coding agents working in this repository.

## First Read

1. `README.md`
2. `docs/SPEC.md`
3. `docs/protocol.md`
4. `docs/architecture/module-graph.md`
5. `docs/decisions/002-architecture.md`
6. `docs/decisions/005-api-shape.md`
7. `docs/error-taxonomy.md`
8. `CONTRIBUTING.md`

Use links above as source of truth; do not restate their contents in PR descriptions or generated docs unless needed.

## Environment

- JDK 21.
- Android SDK API 35 for Android targets (`ANDROID_HOME` set).
- Xcode 15+ for iOS targets.
- Clone with submodules (`--recurse-submodules`) because protobuf definitions are vendored.

If protocol/generated symbols look wrong, verify submodule state under `proto/src/protobufs`.

## Commands Agents Should Run

- Full gate: `./gradlew check`
- Unit tests: `./gradlew jvmTest` and, when relevant on macOS, `./gradlew iosSimulatorArm64Test`
- Lint: `./gradlew detekt spotlessCheck`
- Format: `./gradlew spotlessApply`
- Architecture rules: enforced by detekt `ForbiddenImport` + the
  `:core:verifyModuleBoundary` Gradle task (both wired into `check`).
  See [ADR-008](docs/decisions/008-architecture-enforcement.md) and the
  matrix in [`docs/architecture/enforcement.md`](docs/architecture/enforcement.md).
- API compatibility: `./gradlew checkKotlinAbi`
- API baseline refresh (only when public API intentionally changes): `./gradlew updateKotlinAbi`
- Agent/tooling guardrails: `bash .github/tooling/check.sh`

Prefer targeted tasks while iterating, then run `./gradlew check` before finishing.

## Hard Architectural Rules

- Respect module boundaries from `docs/architecture/module-graph.md`.
- `:core` must depend only on `:proto`.
- Do not add transport/storage implementation dependencies into `:core`.
- Engine concurrency model is single-writer actor; do not introduce mutex/atomic/synchronized patterns in engine paths (see ADR-002).
- No `java.*` or `android.*` imports in `commonMain`; use `kotlinx-io` for byte payloads and `kotlinx-datetime` for time.
- Transport-side locks/atomics (lifecycle/handle ownership only) are allowed but MUST carry an inline `// ADR-012: native-handle ownership` or `// ADR-012: lifecycle idempotency` comment; see `docs/decisions/012-transport-threading.md`.

## Public API Rules

- Follow API shape rules in ADR-005.
- Do not introduce `kotlin.Result<T>` in public API.
- If public API changes are intentional, include regenerated `api/*.api` files from `updateKotlinAbi`.
- Every public symbol MUST have a KDoc comment; Dokka coverage is a CI gate (`./gradlew dokkaHtml`).

## Workflow Expectations

- Keep changes minimal and scoped.
- Add or update tests for behavioral changes.
- Update docs under `docs/` when behavior, protocol handling, or contributor workflow changes.
- Sign commits with DCO (`git commit -s`) when creating commits.

## Where To Look By Task

- Wire protocol behavior: `docs/protocol.md`
- Error mapping and failure semantics: `docs/error-taxonomy.md`
- CI expectations and local equivalents: `docs/ci-cd.md`
- Versioning and SemVer/API policy: `docs/versioning.md`
- Manual device verification scenarios: `docs/manual-tests.md`
- Test pyramid + conventions: `docs/testing.md`
- Logging integration (`LogSink` SAM, `enableFrameLogging` opt-in): `docs/decisions/011-logsink.md`
- Transport-side threading contract (blocking I/O, Flow bridging, lock carve-outs): `docs/decisions/012-transport-threading.md`
- CLI JSON output format (NDJSON envelope for agent/eval scripting): `docs/decisions/013-proto-json-envelope.md`
- Storage PRAGMA strategy per driver: `docs/decisions/014-storage-pragmas.md`
- Test fakes and doubles: `testing/` module (`InMemoryStorage`, `FakeRadioTransport`)

## Agent Tooling In Repo

Slim by design. The full inventory:

- **This file (`AGENTS.md`)** — guardrails + routing matrix below.
- **`CLAUDE.md`** — companion pointer file for runners that key off the
  Claude filename. Identical content to this file (kept in sync).
- **`GEMINI.md`** — companion pointer file for Gemini runners. Kept in sync.
- **Skills** (`.github/skills/`) — invokable workflow recipes; one
  `SKILL.md` per pack. See `.github/skills/README.md` for the index.
- **One agent** (`.github/agents/spec-guard.agent.md`) — full
  spec-compliance review. Other "agent" workflows are now skills.
- **One prompt** (`.github/prompts/pre-pr-sanity.prompt.md`) — final
  pre-PR sweep. Other prompts collapsed into the matching skills.
- **Eval harness** (`.github/evals/`) — scoring smoke check for prompts.
- **Tooling guardrails** (`.github/tooling/`) — `check.sh` enforces
  schema validity, frontmatter, SHA-pinned actions, and so on.
- **Structured agent report schema**:
  `.github/tooling/agent-report.schema.json` (+ matching
  `agent-report.template.json`).

### Task → skill / agent routing matrix

| Task | Use |
|---|---|
| Public API change (new types, signature edits, ADR-005 shape work) | skill `api-compat-guardian` |
| Wire framing, handshake, transport semantics, protocol mapping | skill `protocol-change-checklist` |
| New transport module (BLE/serial/TCP/MQTT) | skill `transport-module-authoring` |
| Behavior/protocol/architecture/API changed → docs need to track | skill `docs-sync-guard` |
| Local or CI gate failed — classify and fix minimally | skill `ci-failure-triage` |
| Pre-tag readiness sweep (gates, SemVer, ADRs, runbook) | skill `release-readiness` |
| Cross-cutting "is this change spec-compliant?" review | agent `spec-guard` |
| Final pre-PR quality sweep | prompt `pre-pr-sanity` |

## Common Pitfalls

- Forgetting submodules leads to proto/codegen failures.
- Running `checkKotlinAbi` without `updateKotlinAbi` after intentional public API edits causes CI failure.
- Cross-platform changes should consider target matrix constraints documented in `docs/architecture/module-graph.md`.
- `Dispatchers.IO` from `commonMain` fails on Native/iOS ("it is internal"); use `expect/actual` per-platform dispatchers instead (see `storage-sqldelight/src/{jvm,android,apple}Main/.../StorageDispatcher.*.kt`).
- Wire-generated proto fields are `snake_case` (e.g., `user.long_name`), not `camelCase` — do not rename them.
- Omitting `@KDoc` on public symbols will fail the Dokka CI gate.
- Transport-side `Mutex`/atomic without an `// ADR-012:` annotation will surface in code review as an apparent ADR-002 violation.
