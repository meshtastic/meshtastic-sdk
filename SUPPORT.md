# Support

Thanks for using `meshtastic-sdk`. Here's how to get help.

## Before You Ask

1. Check the [README](README.md) and the docs under [`docs/`](docs/) — start
   with [`docs/SPEC.md`](docs/SPEC.md), [`docs/protocol.md`](docs/protocol.md),
   and [`docs/error-taxonomy.md`](docs/error-taxonomy.md).
2. Search existing
   [issues](https://github.com/meshtastic/meshtastic-sdk/issues) and
   [discussions](https://github.com/meshtastic/meshtastic-sdk/discussions) —
   your question may already be answered.
3. Re-run with the submodule fully initialized
   (`git submodule update --init --recursive`) — most "missing proto symbol"
   reports trace back to a stale submodule.

## Where to Ask

| Need | Channel |
| --- | --- |
| **Usage question** ("how do I…") | GitHub Discussions |
| **Bug report** (reproducible misbehavior) | GitHub Issues — use the bug template |
| **Feature request** | GitHub Issues — use the feature template |
| **Security vulnerability** | See [`SECURITY.md`](SECURITY.md) — do **not** open a public issue |
| **General Meshtastic ecosystem questions** | The official [Meshtastic Discord and forums](https://meshtastic.org) |

## Filing a Good Bug Report

Include:

- SDK version (`gradle.properties` `version` or the published artifact coords).
- Target platform (JVM, Android API level, iOS version).
- Transport in use (BLE / TCP / serial) and device firmware version.
- Minimal reproducible snippet — ideally a failing test in
  [`samples/cli`](samples/cli) or a small Gradle project.
- The full stack trace, plus the `LogSink` output if you have one wired up
  (see [`docs/observability.md`](docs/observability.md) once available).

## Response Times

This is a volunteer-maintained project. We aim to triage new issues within a
week, but there are no guarantees. Sponsoring or contributing review/PR work
is the fastest way to move something up the queue — see
[`CONTRIBUTING.md`](CONTRIBUTING.md).
