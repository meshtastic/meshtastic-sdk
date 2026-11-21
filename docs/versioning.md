# Versioning, ABI and release policy

How SDK versions are numbered, what each bump allows, and how the ABI dumps enforce it. The tooling ([ADR-003](./decisions/003-tooling.md)) is `axion-release-plugin` for git-tag-driven SemVer, the Kotlin Gradle plugin's built-in ABI validation, and the vanniktech `maven-publish-plugin` publishing straight to the Sonatype Central Portal.

## SemVer

`MAJOR.MINOR.PATCH`, derived from the latest annotated `vX.Y.Z` tag:

| Bump | When |
|---|---|
| **MAJOR** | A source- or binary-incompatible change to `:core` or a storage interface. A new subtype of a public sealed type, since it breaks a consumer's exhaustive `when`. Moving the `org.meshtastic:protobufs` artifact across an upstream major change. |
| **MINOR** | New public API, including a new optional `Builder` method; a new transport module; moving the `org.meshtastic:protobufs` artifact across an upstream minor change (the common case: a new `PortNum` or `Config` field). |
| **PATCH** | A bug fix, internal refactor or doc-only change; moving the `org.meshtastic:protobufs` artifact across an upstream patch change (an additive field with a default). |

Every push to `main` publishes a snapshot (`X.Y.Z-SNAPSHOT`, the patch after the latest tag) to the [Central Portal snapshot repository](https://central.sonatype.com/repository/maven-snapshots/). Releases publish to Maven Central from `release.yml`, as [`RELEASING.md`](../RELEASING.md) describes.

## Pre-1.0 policy

While the version is `0.x.y`:

1. Breaking changes are allowed between minor versions (`0.1.x → 0.2.0`).
2. Every breaking change must:
    - Bump MINOR.
    - Regenerate the `api/` dumps with `./gradlew updateKotlinAbi` in the same change.
    - Add a `*.breaking.md` changelog fragment, so the `## [0.MINOR.0]` section the cut writes has a `### Breaking` subsection.
    - Appear as `**BREAKING**` in the GitHub release notes. The release workflow adds that line for any version whose changelog section has `### Breaking`.
3. Deprecation is optional but encouraged where a consumer can adapt: mark the symbol `@Deprecated(level = WARNING)` for one minor version, then remove it.
4. ABI validation is a hard gate in PR CI. A PR that changes the public surface commits the regenerated dumps.

## 1.0 and later

From `1.0.0`:

1. No breaking change to public API in a non-major release.
2. ABI validation stays a hard gate.
3. Removal cycle: deprecate in `N.M`, remove in `(N+1).0`.
4. A new case in a public sealed type is breaking for consumers with an exhaustive `when`, so it ships only in a major release. Review catches it in `:core` PRs; no automated rule does ([ADR-005](decisions/005-api-shape.md)).
5. A new `MeshtasticException` subclass is breaking for the same reason, so new error categories ship in a major release.

## ABI tooling

The Kotlin Gradle plugin's built-in ABI validation replaces the standalone `binary-compatibility-validator` plugin:

- `./gradlew checkKotlinAbi` fails if the committed dumps differ from the public surface. `check` runs it.
- `./gradlew updateKotlinAbi` regenerates the dumps; commit the result.
- Each module has a JVM dump (`api/jvm/<module>.api`) and a klib dump (`api/<module>.klib.api`) covering its iOS targets and, where built, `js` and `wasmJs`.
- `build-logic/convention/src/main/kotlin/PublishingConventionPlugin.kt` configures it once for every published module.

### Proto types and ABI validation

Protobuf types aren't part of the SDK's dumped surface. They come from the external `org.meshtastic:protobufs` artifact, not an in-tree module, so the validator never dumps them, and Wire's generation rules govern their compatibility upstream. `:core` re-exports them with `api(libs.meshtasticProtobufs)`, and `:core:verifyModuleBoundary` keeps that the only declared path, so the proto surface is defined in one place.

## Proto artifact policy

The `org.meshtastic:protobufs` version pinned in [`../gradle/libs.versions.toml`](../gradle/libs.versions.toml) (`meshtasticProtobufs`) is how the SDK tracks the wire schema; every new firmware feature arrives through it.

| Upstream proto change | SDK bump |
|---|---|
| New optional field on an existing message | PATCH |
| New `PortNum` value | MINOR (a new payload becomes routable) |
| New `Config.*` or `ModuleConfig.*` substructure | MINOR |
| A `oneof` gains a case | MINOR |
| Field removal or type change | MAJOR |
| Renumbered enum value | MAJOR |
| New top-level message, such as a new `FromRadio` case | MINOR pre-1.0, because `FromRadio` is transient enough to accept the break to exhaustive handling; MAJOR from 1.0 |

The bump commit must:

- Update `meshtasticProtobufs` in `gradle/libs.versions.toml`.
- Run `./gradlew updateKotlinAbi` to capture any change to `:core`'s surface.
- Name the upstream version, and the commit range if known, in the commit body.
- If a message gained a `oneof` case, audit `WireCodec` and the engine's `FromRadio` and `MeshPacket` handling for exhaustive `when`s over it.

Renovate opens a PR for each new `org.meshtastic:protobufs` version and never automerges it; a maintainer reviews the API diff and merges. See [`../renovate.json`](../renovate.json).

## Release workflow

How a release is cut, which secrets it uses and which gates it runs are in [`RELEASING.md`](../RELEASING.md).

### Published artifacts

| Coordinates | Source module |
|---|---|
| `org.meshtastic:sdk-bom` | `:bom` (Maven BOM aligning the versions in this table) |
| `org.meshtastic:sdk-core` | `:core` (re-exports the `org.meshtastic:protobufs` types through `api`) |
| `org.meshtastic:sdk-transport-ble` | `:transport-ble` |
| `org.meshtastic:sdk-transport-tcp` | `:transport-tcp` |
| `org.meshtastic:sdk-transport-serial` | `:transport-serial` (JVM through jSerialComm; Android through jSerialComm or usb-serial-for-android) |
| `org.meshtastic:sdk-transport-websocket` | `:transport-websocket` |
| `org.meshtastic:sdk-storage-sqldelight` | `:storage-sqldelight` |
| `org.meshtastic:sdk-testing` | `:testing` |

All modules ship at the same version. Each KMP target publishes under its own coordinates (`sdk-core-jvm`, `sdk-core-iosarm64`, `sdk-core-js`, `sdk-core-wasm-js`, and so on) behind the root Gradle module. The `RadioClient` XCFramework that [ADR-007](./decisions/007-ios-distribution.md) describes isn't published.

The roadmap artifacts (`sdk-rpc`, `sdk-transport-rpc`, `sdk-host-rpc-server`, `sdk-transport-mqtt-proxy`) are additive; see [`./future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md).

### BOM

`:bom` is a Maven BOM constraining every artifact in the table to the release version:

```kotlin
implementation(platform("org.meshtastic:sdk-bom:0.2.0"))
implementation("org.meshtastic:sdk-core")           // version from the BOM
implementation("org.meshtastic:sdk-transport-ble")  // version from the BOM
```

axion versions the BOM with everything else. [`bom/README.md`](../bom/README.md) has Groovy and Maven usage.

### Release notes

A release's GitHub notes are its `CHANGELOG.md` section verbatim, under an install snippet. [`CONTRIBUTING.md`](../CONTRIBUTING.md#changelog) says what goes in that section.

## Branching

- `main` is always green, and releases are cut from it.
- `release/X.Y` branches start at 1.0, for backported patch releases. Before 1.0 only `main` exists.
- Feature branches are short-lived and squash-merged into `main`, which has no merge commits.

## Compatibility

| SDK version | Proto artifact | Minimum firmware | Kotlin | Gradle | Android `minSdk` | iOS deployment |
|---|---|---|---|---|---|---|
| `0.x.y` | `meshtasticProtobufs` in `gradle/libs.versions.toml`; the changelog names each bump | `2.3.15` hard floor (`DeviceVersion.ABS_MIN_SUPPORTED`, `connect()` throws `FirmwareTooOld` below it); `2.5.14` recommended (`DeviceVersion.MIN_SUPPORTED`, a `ProtocolWarning` below it). Newer features gate on proto fields (e.g. `DeviceMetadata.hasPKC` for PKI, `ClientNotification` variants for security warnings). | latest stable at release | 9.x | 26 | iOS 14 |

The SDK is forward-compatible for additive proto fields. The only firmware check is the
floor, in two tiers: a `firmware_version` that parses below `2.3.15`
(`DeviceVersion.ABS_MIN_SUPPORTED`) fails the connect with `MeshtasticException.FirmwareTooOld`
(see `docs/error-taxonomy.md`); one from `2.3.15` up to `2.5.13` connects and emits one
`MeshEvent.ProtocolWarning` per session recommending an update to `2.5.14`. A version that is
missing or does not parse never fails the connect.

## Yanking a release

If a release ships a critical defect:

1. Open a hotfix PR that bumps PATCH.
2. Release that patch right away, per [`RELEASING.md`](../RELEASING.md).
3. Maven Central has no unpublish, so the bad version stays. The changelog marks it, for example `## [0.5.0] — YANKED`, with a pointer to `0.5.1` and a short reason.
4. README install snippets always show the latest version that isn't yanked.

## Related

- [ADR-003](./decisions/003-tooling.md): release tooling.
- [ADR-007](./decisions/007-ios-distribution.md): iOS distribution.
- [`api-reference.md`](./api-reference.md): the surface the ABI gate protects.
- [`error-taxonomy.md`](./error-taxonomy.md): sealed hierarchies whose extension is MAJOR.
- [`ci-cd.md`](./ci-cd.md): the workflows that enforce this policy.
- [`future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md): additive artifacts after 1.0.
