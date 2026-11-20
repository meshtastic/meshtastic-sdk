# Support

How to get help with `meshtastic-sdk`.

## Before you ask

1. Check the [README](README.md) and [`docs/`](docs/), starting with [`docs/integration-guide.md`](docs/integration-guide.md), [`docs/protocol.md`](docs/protocol.md) and [`docs/error-taxonomy.md`](docs/error-taxonomy.md).
2. Search the existing [issues](https://github.com/meshtastic/meshtastic-sdk/issues).
3. For a missing or renamed generated proto symbol, check the `meshtasticProtobufs` pin in `gradle/libs.versions.toml`; the types come from the published `org.meshtastic:protobufs` artifact.

## Where to ask

| Need | Channel |
| --- | --- |
| Bug report (reproducible misbehavior) | GitHub Issues, bug template |
| Feature request | GitHub Issues, feature template |
| Security vulnerability | [`SECURITY.md`](SECURITY.md); do not open a public issue |
| Usage and general Meshtastic questions | The [Meshtastic community channels](https://meshtastic.org/docs/community/) |

## Filing a good bug report

Include:

- The SDK version (the artifact coordinates).
- The target platform (JVM, Android API level, iOS version, or browser for JS and Wasm).
- The transport (Bluetooth LE, TCP, serial or WebSocket) and the node's firmware version.
- A minimal reproduction, ideally a [`samples/cli`](samples/cli) command or a small Gradle project.
- The full stack trace, plus `LogSink` output if one is wired up (see [`docs/observability.md`](docs/observability.md)).

## Response times

Volunteers maintain the project. Maintainers aim to triage new issues within a week, without guarantees. Contributing reviews or pull requests is the fastest way to move something up the queue; see [`CONTRIBUTING.md`](CONTRIBUTING.md).
