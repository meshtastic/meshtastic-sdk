# Versioning, ABI, and release policy

> Authoritative tooling per [ADR-003](./decisions/003-tooling.md): `axion-release-plugin` (git-tag-driven SemVer), Kotlin's built-in klib ABI validation (Kotlin 2.2+, formerly the standalone `binary-compatibility-validator`), Vanniktech `maven-publish-plugin` publishing direct to **Sonatype Central Portal** (no OSSRH staging dance).

## SemVer

`MAJOR.MINOR.PATCH`, derived from the most recent annotated git tag (`vX.Y.Z`):

| Bump | When |
|---|---|
| **MAJOR** | Source- or binary-incompatible change to `:core` or any storage interface. Adding a new sealed-class subtype (forces consumer `when` exhaustiveness break). Bumping the `org.meshtastic:protobufs` artifact across a MAJOR upstream change (rare). |
| **MINOR** | New public API; new transport module; bumping the `org.meshtastic:protobufs` artifact across a MINOR upstream change (most common — new `PortNum`, new `Config` field). New optional `Builder` method. |
| **PATCH** | Bug fix; internal refactor; doc-only change; bumping the `org.meshtastic:protobufs` artifact across a PATCH upstream change (additive proto field with default value). |

Snapshot builds (`X.Y.Z-SNAPSHOT`) publish on every push to `main` to the [Central Portal snapshot repository](https://central.sonatype.com/repository/maven-snapshots/); tagged releases publish to Maven Central via manual workflow dispatch.

## Pre-1.0 policy

While version is `0.x.y`:

1. **Breaking changes are allowed** between any two MINOR versions (`0.1.x → 0.2.0`).
2. Every breaking change MUST:
    - Bump MINOR.
    - Regenerate `api/` files via `./gradlew updateKotlinAbi` in the same commit.
    - Add a `## [0.MINOR.0]` section to `CHANGELOG.md` with `### Breaking` subsection.
    - Be called out in the GitHub release notes as `**BREAKING**`.
3. **Deprecation is optional** but encouraged for changes a consumer can adapt to: mark with `@Deprecated(level = WARNING)` for one MINOR, then remove in the next.
4. Kotlin's built-in klib ABI validation runs in PR CI as **hard-gate from Phase 0** (matches `mqtt-client`'s day-1 enforcement). A PR that changes `api/*.api` MUST commit the regenerated dump.

## 1.0+ policy

From `1.0.0`:

1. **No breaking change** to public API in any non-MAJOR release. Period.
2. ABI validation continues as hard-gate; an `api/*.api` change without a MAJOR-bump label fails CI.
3. Removal cycle: deprecate at version `N.M`, remove at version `(N+1).0` (next MAJOR).
4. **Sealed-class additions** count as breaking for consumers using exhaustive `when` — they ship in MAJOR releases only. Code review flags any new sealed-class case in a `:core` PR (no automated rule covers this; reviewers consult [ADR-005](decisions/005-api-shape.md)).
5. **Adding a new throwable subclass** to `MeshtasticException` is breaking for the same reason. New error categories ship as MAJOR.

## ABI tooling

Kotlin 2.2 brings klib ABI validation into KGP itself, deprecating the standalone `kotlinx-binary-compatibility-validator` plugin. We use the built-in mechanism:

- `./gradlew checkKotlinAbi` — fails if `api/*.api` differs from the current public surface.
- `./gradlew updateKotlinAbi` — regenerate; commit the result.
- Per-target `klibApiCheck` runs against each Kotlin/Native and Kotlin/Wasm target's effective ABI, in addition to the JVM `checkKotlinAbi`.
- Configuration lives in `build-logic/convention/MeshtasticKmpPublishPlugin.kt` (one place, applied to every published module).

### Proto types and ABI validation

Protobuf types are **not** part of this SDK's ABI surface: they come from the external
`org.meshtastic:protobufs` artifact, not an in-tree module, so BCV never dumps them, and their
own source/binary compatibility is governed upstream by Wire's generation rules. `:core`
re-exposes them via `api(libs.meshtasticProtobufs)`; `:core:verifyModuleBoundary` keeps that the
single declared path, so the proto surface stays defined in one place (the artifact), not
mirrored in-tree.

## Proto artifact policy

The `org.meshtastic:protobufs` version pinned in [`../gradle/libs.versions.toml`](../gradle/libs.versions.toml) (`meshtasticProtobufs`) is the integration point for every new firmware feature; bumping it is how the SDK tracks the wire schema.

| Upstream proto change | This SDK's bump |
|---|---|
| New optional field on existing message | PATCH |
| New `PortNum` value | MINOR (new app payload becomes routable) |
| New `Config.*` / `ModuleConfig.*` substructure | MINOR |
| Sealed `oneof` extension | MINOR |
| Field removal / type change | MAJOR |
| Renumbered enum value | MAJOR |
| Top-level message added (e.g., new `FromRadio` arm) | MINOR (Wire generates a sealed-class case in `FromRadio.body`; consumers' exhaustive `when`s break — but `FromRadio` is internal/transient enough that we accept this at MINOR pre-1.0; after 1.0 this also escalates to MAJOR) |

The proto bump commit MUST:
- Update the `meshtasticProtobufs` version in `gradle/libs.versions.toml`.
- Run `./gradlew updateKotlinAbi` to capture any change to `:core`'s generated-symbol surface (the BCV-relevant surface).
- Note the upstream version (and commit range, if known) in the bump commit body.
- If a wire message has a new `oneof` arm, audit `WireCodec` and the engine's `FromRadio` / `MeshPacket` handling for places that exhaustively `when` over it.

Renovate's `gradle` manager opens a PR when a new `org.meshtastic:protobufs` version is published; a maintainer reviews the API diff and merges. See [`../renovate.json`](../renovate.json).

## Release workflow

### Tagging

```bash
git tag -a v0.5.0 -m "Release 0.5.0"
git push --tags
```

`axion-release` reads the tag; the resulting `./gradlew currentVersion` returns `0.5.0`.

### Publishing

Vanniktech publishes direct to the Sonatype Central Portal in one shot — there is no separate staging-repository "close-and-release" step.

```bash
# Snapshots (any non-tag build):
./gradlew publishToMavenCentral --no-configuration-cache

# Tagged release:
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

Driven from a manual `release.yml` GitHub Actions workflow (`workflow_dispatch`) on the tagged commit. Required secrets (vanniktech-standard names):

- `MAVEN_CENTRAL_USERNAME` — Central Portal token username.
- `MAVEN_CENTRAL_PASSWORD` — Central Portal token password.
- `SIGNING_IN_MEMORY_KEY` — ASCII-armored GPG private key (`gpg --armor --export-secret-keys $KEY`), newlines preserved.
- `SIGNING_IN_MEMORY_KEY_PASSWORD` — passphrase for the above.

### Artifacts published per release (MVP)

| Coordinates | Source module |
|---|---|
| `org.meshtastic:sdk-bom` | `:bom` (Maven BOM aligning the versions below) |
| `org.meshtastic:sdk-core` | `:core` (re-exports the `org.meshtastic:protobufs` types via `api`) |
| `org.meshtastic:sdk-transport-ble` | `:transport-ble` |
| `org.meshtastic:sdk-transport-tcp` | `:transport-tcp` |
| `org.meshtastic:sdk-transport-serial` | `:transport-serial` (single KMP module covering JVM via jSerialComm and Android via usb-serial-for-android) |
| `org.meshtastic:sdk-storage-sqldelight` | `:storage-sqldelight` |
| `org.meshtastic:sdk-testing` | `:testing` |

All modules ship at the same version. KMP variants (Android AAR, JVM JAR, iOS Klib + XCFramework via [ADR-007](./decisions/007-ios-distribution.md)) publish under the same coordinates with platform classifiers per Gradle convention.

Roadmap artifacts (`sdk-rpc`, `sdk-transport-rpc`, `sdk-host-rpc-server`, `sdk-transport-mqtt-proxy`) ship additively when the wasm/RPC roadmap lands; see [`./future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md).

### BOM

`:bom` is a tiny Maven BOM module declaring `<dependencyManagement>` entries for every published artifact above at the current release version. Consumers can:

```kotlin
implementation(platform("org.meshtastic:sdk-bom:0.5.0"))
implementation("org.meshtastic:sdk-core")           // version comes from BOM
implementation("org.meshtastic:sdk-transport-ble")  // version comes from BOM
```

The BOM is itself versioned and bumped on every release (axion handles this uniformly).

### Release notes

Generated from `CHANGELOG.md` `## [X.Y.Z]` section + a manually-curated highlight blurb. Format:

```
## [0.5.0] — 2026-MM-DD

### Added
- ...

### Changed
- ...

### Breaking
- ...   (only pre-1.0; post-1.0 these only appear in MAJOR releases)

### Fixed
- ...

### Proto
- Bumped `org.meshtastic:protobufs` to <version> (upstream range <prev>..<sha>).
```

## Branching

- `main` — always green; tags are cut from here.
- `release/X.Y` — long-lived once we ship `1.0`, for back-port patch releases. Pre-1.0 only `main` exists.
- Feature branches are short-lived; rebase + squash-merge into `main` (no merge commits in `main`'s linear history).

## Compatibility matrix

Documented in `docs/compatibility.md` (TBD) and the README:

| SDK version | Proto artifact (`meshtasticProtobufs`) | Min firmware (capability-gated) | Kotlin | Gradle | Min Android SDK | iOS deployment |
|---|---|---|---|---|---|---|
| `0.x.y` | pinned in `gradle/libs.versions.toml` (see CHANGELOG for the version) | No hard minimum; capabilities gate on proto fields (e.g. `DeviceMetadata.hasPKC` for PKI, `ClientNotification` variants for security warnings). Practical floor tracks the pinned proto artifact — currently ≈ firmware `2.6.x`. | latest stable at release | 9.x | 26 | iOS 14 |

The SDK is forward-compatible for additive proto fields and does **not** hard-fail on
firmware-version mismatch today. `MeshtasticException.FirmwareTooOld` is reserved for a
future opt-in check (see `docs/error-taxonomy.md`). `docs/compatibility.md` will be added
when a real hard minimum is enforced.

## Yanking a release

If a release ships a critical defect:

1. Open a hotfix PR; bump PATCH.
2. Cut a new tag immediately.
3. The bad version is **not** unpublished from Maven Central (Central does not support unpublish). Instead, the CHANGELOG marks it `## [0.5.0] — YANKED` with a pointer to `0.5.1` and a brief reason.
4. README installation snippets always show the latest non-yanked version.

## Related

- [ADR-003](./decisions/003-tooling.md) — release tooling rationale
- [ADR-007](./decisions/007-ios-distribution.md) — iOS distribution (KMMBridge)
- [`api-reference.md`](./api-reference.md) — what the ABI gate actually protects
- [`error-taxonomy.md`](./error-taxonomy.md) — sealed hierarchies whose extension is MAJOR
- [`ci-cd.md`](./ci-cd.md) — the workflows that enforce this
- [`future/wasm-rpc-roadmap.md`](./future/wasm-rpc-roadmap.md) — additive artifacts deferred to post-1.0
