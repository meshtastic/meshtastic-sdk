# ADR-015: Consume the published `org.meshtastic:protobufs` artifact instead of vendoring the schema

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-06-13 |
| **Deciders** | Maintainers |
| **Supersedes** | The proto-*sourcing mechanism* of [ADR-001](001-public-api-uses-generated-protobufs.md) and [ADR-006](006-multi-module-rationale.md). Those decisions stand; only *how* the proto types are produced changes. |
| **Related** | [ADR-001](001-public-api-uses-generated-protobufs.md), [ADR-003](003-tooling.md), [ADR-006](006-multi-module-rationale.md), [`../versioning.md`](../versioning.md), [`../architecture/module-graph.md`](../architecture/module-graph.md) |

## Context

[ADR-001](001-public-api-uses-generated-protobufs.md) made the Wire-generated protobuf types the SDK's public data model, and the original module plan ([ADR-006](006-multi-module-rationale.md)) realised that by:

- vendoring `meshtastic/protobufs` as a git submodule at `proto/src/protobufs`, and
- generating it in-tree into a public `:proto` Gradle module (Wire codegen via a `meshtastic.proto` convention plugin), which `:core` re-exported with `api(project(":proto"))`.

Since then, the Meshtastic org began **publishing the generated bindings directly** as the `org.meshtastic:protobufs` Maven artifact — a Kotlin Multiplatform library (the same Wire output, built for all of the SDK's targets) versioned to track the schema. That makes the in-tree submodule + codegen redundant: the SDK would be regenerating, locally, bytes that are now a normal published dependency.

Forces in tension:
- ADR-001's contract ("Wire types *are* the public API") must be preserved exactly — consumers still `import org.meshtastic.proto.*`.
- Contributor and CI setup should be as light as possible (no submodule init, no local Wire codegen step).
- Reproducible builds require an immutable version pin.

## Decision

The SDK **depends on the published `org.meshtastic:protobufs` Maven artifact** for all protobuf types. There is no `proto/src/protobufs` git submodule and no in-tree `:proto` Gradle module.

- The version is pinned in [`../../gradle/libs.versions.toml`](../../gradle/libs.versions.toml) as `meshtasticProtobufs`.
- `:core` declares `api(libs.meshtasticProtobufs)`, so `org.meshtastic.proto.*` reaches every downstream module transitively — exactly the surface `api(project(":proto"))` used to provide. `:core:verifyModuleBoundary` keeps that the single declared path.
- Bumping the schema is now an ordinary Gradle dependency bump (Renovate's `gradle` manager), followed by `./gradlew updateKotlinAbi` when `:core`'s generated-symbol surface shifts. See [`../versioning.md`](../versioning.md) → "Proto artifact policy".

ADR-001 is unchanged in substance: the generated types are still **the** public data model; only their provenance moved from in-tree codegen to a published artifact.

## Rationale

- **No local codegen or submodule.** Clones are a plain `git clone`; CI drops `submodules: recursive` and the Wire Gradle plugin. The artifact is the same Wire output, produced once upstream.
- **One contract, one source.** The proto surface is defined in exactly one place (the artifact), not shadowed by a local generation step that could drift from the upstream publish.
- **Standard dependency hygiene.** Version pinning, Renovate automation, and reproducibility work the same way they do for every other dependency.

## Alternatives considered

| Option | Why not |
|---|---|
| Keep the submodule + `:proto` module | Regenerates, locally, bytes that are now published; costs every contributor a submodule init and the build a codegen step, for no benefit now that upstream publishes the KMP artifact. |
| Consume the artifact but keep a thin `:proto` module that re-exports it | Pointless indirection — `:core`'s `api(...)` already re-exports the types; a wrapper module adds a publish coordinate with zero value. |

## Consequences

### Positive
- Simpler clone and CI; no submodule, no Wire Gradle plugin, no `:proto` publish coordinate.
- Proto provenance is a normal, automatable, pin-able dependency.

### Negative / costs
- The SDK can only build against schema versions that have been **published** as `org.meshtastic:protobufs`. Early integration against an unpublished `develop` requires the org's `develop-SNAPSHOT`, which **must not back a tagged release** (a moving snapshot breaks reproducible builds — see CHANGELOG and `versioning.md`).
- BCV no longer dumps proto types at all (they are an external dependency, not an in-tree module); their source/binary compatibility is governed upstream by Wire's rules.

### Follow-ups
- [x] `gradle/libs.versions.toml` carries `meshtasticProtobufs`; `:core` uses `api(libs.meshtasticProtobufs)`.
- [x] Docs synced: `AGENTS.md`, `GEMINI.md`, `CONTRIBUTING.md`, `README.md`, `docs/versioning.md`, `docs/ci-cd.md`, `docs/architecture/module-graph.md`, `renovate.json`.
- [x] `versioning.md` "Proto artifact policy" replaces the former "Proto submodule policy".

## References

- [ADR-001](001-public-api-uses-generated-protobufs.md) — Wire types are the public data model (still in force).
- [ADR-006](006-multi-module-rationale.md) — module layout (the `:proto` node is now the external artifact).
- `org.meshtastic:protobufs` — published on Maven Central.
