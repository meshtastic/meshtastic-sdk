# Release Runbook

Step-by-step playbook for cutting a release of `meshtastic-sdk`. The
SemVer policy that *governs* what kind of release is appropriate lives in
[`docs/versioning.md`](versioning.md); this document is the *mechanics*.

## Prerequisites

- You are listed in [`CODEOWNERS`](../CODEOWNERS).
- `main` is green (latest CI run on the tip commit is ✅).
- You have a clean working tree (`git status` is empty).
- You have signing credentials for Maven Central in your environment if
  cutting a stable release (`SIGNING_KEY`, `SIGNING_PASSWORD`,
  `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`). Snapshot releases
  do not need these.

## Decide the bump

Open the diff since the last tag:

```bash
git --no-pager log --oneline $(git describe --tags --abbrev=0)..HEAD
```

Pick MAJOR / MINOR / PATCH per [`docs/versioning.md`](versioning.md). If
unclear, ask a second maintainer in the PR thread *before* tagging.

## Pre-release checklist

Run from a clean tree:

```bash
./gradlew clean
./gradlew check                  # full gate: build, lint, detekt, BCV, tests
./gradlew :core:verifyModuleBoundary  # ADR-008 — also part of `check`
./gradlew checkKotlinAbi               # confirm api/*.api dumps are committed
./gradlew publishToMavenLocal    # smoke test publishing layout
bash .github/tooling/check.sh    # agent/tooling guardrails
```

If `checkKotlinAbi` fails because public API intentionally changed, run
`./gradlew updateKotlinAbi`, commit the regenerated `api/*.api`, push, and start
this checklist over.

### Real-radio conformance sweep

A passing `./gradlew check` is necessary but not sufficient — every release
candidate must also pass the bench-radio acceptance sweep. Run from a connected
host with the bench radio reachable on the same network:

```bash
./gradlew :samples:cli:installDist
samples/cli/build/install/cli/bin/cli conformance \
    --transport=tcp:meshtastic.local \
    --peer-node='!aabbccdd' \
    --candidate=vX.Y.Z-rc1 \
    --output MANUAL-TEST-RESULTS.md
```

The command exits 0 only if every scenario PASSes (SKIPs are allowed when
prerequisites like `--peer-node` aren't supplied). The transcript at
`MANUAL-TEST-RESULTS.md` is the audit trail for the release; commit it under
`docs/release-history/` if you're keeping per-RC records, otherwise replace it
each cycle. See [`samples/cli/README.md`](../samples/cli/README.md#pre-release-conformance-sweep)
for the full set of flags.

## Update CHANGELOG

Move everything under `## [Unreleased]` to a new `## [vX.Y.Z] - YYYY-MM-DD`
heading. Re-create an empty `## [Unreleased]` section above it with the
standard `### Added / ### Changed / ### Removed / ### Fixed` subsections.

If this is a pre-1.0 MINOR with breaking changes, add a `### Breaking`
subsection at the top of the new release section, per
[`docs/versioning.md`](versioning.md) §Pre-1.0 policy.

Commit:

```bash
git add CHANGELOG.md
git commit -s -m "docs(changelog): prepare vX.Y.Z release notes"
```

## Tag and push

axion-release derives the version from the most recent annotated tag:

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin main --tags
```

Confirm: `./gradlew currentVersion` should print `X.Y.Z` (no `-SNAPSHOT`
suffix) on the tagged commit.

## Publish

Stable releases publish via the manual `release` workflow in
[`.github/workflows/`](../.github/workflows/) — trigger it via
`gh workflow run release.yml -f version=X.Y.Z` or the GitHub Actions UI.

> **Currently disabled.** All workflows are suffixed `.yml.disabled` for
> the internal 0.1.0 team-share; until they're re-enabled, run the
> publish steps locally (`./gradlew publish`) per the workflow's
> intended sequence below.

The workflow:

1. Re-runs `./gradlew check`.
2. Runs `./gradlew publishAggregationToCentralPortal` (per
   [`docs/ci-cd.md`](ci-cd.md)).
3. Promotes the staged release on Sonatype Central Portal.

Snapshot releases publish automatically on every push to `main` — no
manual step needed.

### Re-running after a partial failure

The publish step is idempotent. Before uploading, the workflow probes
`repo1.maven.org` for this version's `sdk-core` POM and skips the upload
when it is already there (Sonatype rejects re-uploads with "component
already exists"). If a run dies mid-workflow — runner outage, GitHub
incident — a plain re-run of the same workflow is safe, whatever state
the previous run reached.

One caveat: Sonatype → `repo1.maven.org` sync takes roughly 10–25
minutes. A re-run started inside that window can still attempt the
upload and fail on "component already exists"; wait for the sync (check
`https://repo1.maven.org/maven2/org/meshtastic/sdk-core/`), then re-run.

## After the release

1. **Create the GitHub release**: `gh release create vX.Y.Z --notes-file
   <(awk '/^## \[vX.Y.Z\]/,/^## \[/' CHANGELOG.md | head -n -1)` — verify
   the body before publishing.
2. **Mark breaking changes** with the `**BREAKING**` prefix on the
   release notes title if applicable.
3. **Verify the artifact**: `./gradlew dependencyInsight --dependency
   org.meshtastic:sdk-core:X.Y.Z` from a fresh consumer project
   pointing at Maven Central. It should resolve without `-SNAPSHOT`.
4. **Announce** in the channels listed in [`SUPPORT.md`](../SUPPORT.md).

## Rollback / yank

Maven Central artifacts are immutable — there is no yank. If a release
ships a critical bug, cut a PATCH release immediately with the fix and
update [`SECURITY.md`](../SECURITY.md) if the issue is security-relevant.
For pre-1.0 releases that are unsalvageable, document the `0.X.Y` tag as
withdrawn in `CHANGELOG.md` and bump the MINOR.

## Hotfix procedure

Cherry-pick the fix onto a `release/X.Y` branch off the offending tag,
re-run the pre-release checklist, tag `vX.Y.(Z+1)`, and push. Hotfixes
follow the same publish path as a normal stable release.

## References

- [`docs/versioning.md`](versioning.md) — SemVer policy.
- [`docs/ci-cd.md`](ci-cd.md) — workflow definitions and the publish task.
- [ADR-003](decisions/003-tooling.md) — axion-release + vanniktech +
  Central Portal rationale.
- [`CHANGELOG.md`](../CHANGELOG.md)
