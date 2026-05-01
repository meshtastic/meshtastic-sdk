# ADR-011: `LogSink` — single SAM interface, host-owned routing

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-04-19 |
| **Deciders** | Maintainers |
| **Supersedes** | — |
| **Related** | [ADR-005](005-api-shape.md), [`docs/SPEC.md`](../SPEC.md) §3 |

## Context

Most KMP libraries either ship Kermit / SLF4J / java.util.logging directly
or invent their own logger object. Both cause real problems for our consumers:

- **Shipping a logger** means every host app pays for a transitive
  dependency it may already have a different version of, and we end up
  arguing about log-level taxonomies forever.
- **Inventing a logger** with `LogLevel`, `Tag`, `Throwable`, and a fluent
  builder commits us to maintaining a complete logging framework.

We also need to keep wire-byte / frame-level dumps out of the default log
stream — they can leak PSKs and session passkeys (see audit finding B6).

## Decision

`:core` exposes one `fun interface LogSink` with a single
`log(level: LogLevel, tag: String, message: String, throwable: Throwable?)`
method. Hosts construct an implementation and pass it to
`RadioClient.Builder.logger(sink)`. We ship no default sink other than a
no-op; samples bind Kermit to `LogSink` as a tiny adapter.

Frame-level / wire-byte logging is a **separate**, opt-in, level-gated
hook (`enableFrameLogging`) — never folded into the general `LogSink` —
so credentials cannot leak via a casual `Debug` setting.

## Rationale

- A single SAM interface in pure Kotlin: zero dependencies cross the
  module boundary.
- Hosts already have a logger; we route into it instead of competing with it.
- The opt-in frame logger keeps the dangerous knob explicit and
  documentable.
- ADR-005 forbids `kotlin.Result` and rich exception types in public API;
  `LogSink` is the matching choice for diagnostics — a passive sink, not a
  return-channel.

## Alternatives considered

| Option | Why not |
|---|---|
| Bundle Kermit | Forces a Kermit-specific dependency on every consumer; downstreams already have logging frameworks. |
| Bundle SLF4J | Wrong fit for KMP — no native targets. |
| Static `Logger` object | Untestable; couples every module to a global. |
| Folded frame logging | Audit B6 — high risk of credential leakage via a default `Debug` log level. |

## Consequences

### Positive
- Public API is one interface with one method; trivial to mock in tests.
- Sample `KermitLogSink` shows the wiring without forcing the dependency
  on consumers.
- Frame-level logging stays a deliberate, documented opt-in.

### Negative / costs
- Consumers who don't read the docs see no logs by default (acceptable —
  matches how we'd want a credential-bearing library to behave).
- We have to maintain `LogLevel` enum stability under BCV.

### Follow-ups
- [ ] Add a `samples/cli` `--log-level` flag wired to a stdout `LogSink`
      so the CLI demonstrates the integration pattern.
- [ ] Document `enableFrameLogging` and its redaction expectations in
      `docs/observability.md` (F7).

## References

- [`core/src/commonMain/kotlin/org/meshtastic/sdk/Logging.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Logging.kt)
- Kermit: <https://kermit.touchlab.co/>
