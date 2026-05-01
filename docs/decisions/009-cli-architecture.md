# ADR-009: CLI as a first-class agent-friendly test harness

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-04-19 |
| **Deciders** | Maintainers |
| **Supersedes** | — |
| **Related** | [ADR-006](006-multi-module-rationale.md), [ADR-013](013-proto-json-envelope.md), [`docs/architecture/module-graph.md`](../architecture/module-graph.md) |

## Context

The original `samples/cli` was a developer-only Mosaic TUI demo. Once the
project gained TCP, BLE, and serial transports — each with subtly different
failure modes — the team and our agentic tooling needed something more than a
demo: a single executable that can drive any transport against any device,
emit machine-readable output, and exit with conventional status codes so it
can be wrapped by scripts, eval harnesses, and CI.

Forces in tension:
- **Human ergonomics** vs **agent ergonomics** — pretty interactive output
  vs structured JSON.
- **Discoverability** (built-in TUI, `help` command) vs **scripting**
  (deterministic argv, no terminal needed).
- **Coverage** — the CLI must be able to exercise every transport without
  pulling Android-only deps onto the JVM classpath.

## Decision

`samples/cli` is the SDK's reference test harness. It ships a single binary
with a [Clikt][clikt]-powered subcommand tree (`scan`, `info`, `nodes`,
`packets`, `events`, `send`, `health`, `probe`, `tui`) that share one engine
instance per invocation, accept transport configuration via a unified
`--transport=<spec>` flag, and emit either human-readable text or a stream of
newline-delimited JSON envelopes ([ADR-013](013-proto-json-envelope.md))
controlled by the root-level `--json` flag. The CLI lives in `samples/cli`
(not a top-level module) and is JVM-only.

[clikt]: https://ajalt.github.io/clikt/

## Rationale

- A single binary keeps the agent prompt simple: one tool, one help page,
  predictable exit codes.
- Sub-commands map directly to the SDK's public surface (`RadioClient` +
  the experimental admin/telemetry/routing channels), so the CLI is an
  always-up-to-date executable form of the API.
- JSON-mode (`--json`) makes every subcommand output a deterministic stream
  that an evaluator can diff against a golden record.
- Argv parsing, `--help` generation, exit-code handling, and context
  propagation are delegated to Clikt so there is no hand-rolled parser to
  drift from the documented surface.
- Keeping it under `samples/` reinforces that we publish the SDK, not the
  CLI — downstream apps embed the SDK directly.

## Alternatives considered

| Option | Why not |
|---|---|
| Multiple per-transport CLIs | Triples the surface area to document and ship; agents have to pick the right binary. |
| Make the CLI a published Maven module | Encourages the wrong consumption pattern; we'd then owe a CLI compatibility contract. |
| TUI-only (no JSON mode) | Unusable from scripts/agents; can't be golden-tested. |
| Hand-rolled argv parser | Repeated drift between `Main.kt` dispatch, `Help.kt`, per-command `usage()` blocks, and the `probe all` parser; replaced with Clikt. |

## Consequences

### Positive
- Manual + automated verification share one code path.
- Bug repros become "run this CLI invocation" rather than "write a Kotlin
  snippet."
- The CLI itself becomes a regression target — `./gradlew :samples:cli:run`
  in CI exercises every transport end-to-end.

### Negative / costs
- A second consumer of the public API surface that we have to keep
  building. (Mitigated: the CLI is a small wrapper; most logic stays in
  `:core`.)
- Mosaic + Clikt + jSerialComm pulled into the build, but only into
  `samples/cli`, never into `:core` or any transport module.

### Follow-ups
- [ ] Track CLI flags in `docs/manual-tests.md` whenever we add a sub-command.
- [ ] Wire `cli probe all` into a nightly job once we have hosted devices.

## References

- [ADR-013](013-proto-json-envelope.md) — JSON envelope emitted under `--json`.
- [`samples/cli/README.md`](../../samples/cli/README.md) — user-facing catalogue.
- [`samples/cli/src/main/kotlin/org/meshtastic/cli/`](../../samples/cli/src/main/kotlin/org/meshtastic/cli/)
