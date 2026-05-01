# Releasing meshtastic-sdk

This document explains how a release of `meshtastic-sdk` is cut and published.

## Versioning

Versions follow [SemVer 2.0.0](https://semver.org/spec/v2.0.0.html).
Pre-1.0 SemVer is interpreted per [`docs/versioning.md`](docs/versioning.md):
breaking changes bump MINOR, additive changes bump PATCH.

The project version is **derived from git tags** by
[axion-release](https://github.com/allegro/axion-release-plugin),
configured in the root `build.gradle.kts`. Tag scheme: `vMAJOR.MINOR.PATCH`.

- Exact tag on `HEAD` → version is `MAJOR.MINOR.PATCH` (publishable).
- Commits past the latest tag, or a dirty tree → `MAJOR.MINOR.PATCH-SNAPSHOT`.
- Override at runtime: `./gradlew publish -Prelease.version=X.Y.Z`.

Confirm the resolved version at any time:

```sh
./gradlew currentVersion
```

## Branching

Trunk-based. Releases are tagged on `main`. There is no long-lived
release branch — hotfixes branch from the release tag, land on `main`,
and a new patch tag is cut.

## Release checklist

1. **Sync `main` and run the gate:**
   ```sh
   git checkout main && git pull --ff-only
   ./gradlew check
   ```
2. **Promote the changelog.** In [`CHANGELOG.md`](CHANGELOG.md), move the
   contents of the `[Unreleased]` section into a new `[X.Y.Z] — YYYY-MM-DD`
   section, leaving an empty `[Unreleased]` block above it.
3. **(If public API changed)** confirm the regenerated baselines are
   committed:
   ```sh
   ./gradlew updateKotlinAbi
   git status -- '**/api/*.api' '**/api/*.klib.api'
   ```
4. **Commit the changelog bump:**
   ```sh
   git commit -s -m "chore(release): vX.Y.Z"
   ```
5. **Tag and push:**
   ```sh
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin main
   git push origin vX.Y.Z
   ```
6. **Publish.** All workflows are currently disabled (`.yml.disabled`)
   for the internal 0.1.0 team-share, so publish manually:
   ```sh
   ./gradlew publish
   ```
   Once `.github/workflows/release.yml` is re-enabled before the public
   push, pushing the tag will trigger
   `publishAllPublicationsToInternalRepository` automatically.

## Hotfixes

1. `git checkout -b hotfix/vX.Y.Z+1 vX.Y.Z` from the affected tag.
2. Land the fix; merge or cherry-pick onto `main`.
3. From `main`, run the standard release checklist with the next patch number.

## Rolling back

A tag is the only durable artifact. To withdraw a release, publish a
`X.Y.Z+1` that supersedes it and yank the broken artifact from the
Maven repository per its policy. Do **not** delete or move tags once
pushed — downstream consumers may have pinned to them.

## Related

- [`docs/versioning.md`](docs/versioning.md) — SemVer interpretation, ABI policy
- [`docs/ci-cd.md`](docs/ci-cd.md) — workflows and gates
- [`docs/decisions/004-licensing.md`](docs/decisions/004-licensing.md) — license terms publishers must keep intact
