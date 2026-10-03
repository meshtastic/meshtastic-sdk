# Releasing

meshtastic-sdk publishes every `org.meshtastic:sdk-*` artifact, `sdk-bom` included, to
Maven Central at one version with the vanniktech maven-publish plugin, from
[`.github/workflows/release.yml`](.github/workflows/release.yml). Every push to `main`
also publishes a `-SNAPSHOT` from `ci.yml`.

## Version

[axion-release](https://github.com/allegro/axion-release-plugin) derives the version from
the `vX.Y.Z` tag; there is no version file. On a tagged commit `./gradlew currentVersion`
prints `X.Y.Z`; past the tag it prints the next patch with `-SNAPSHOT`. The release
workflow creates the annotated tag, so nobody tags by hand. Tags are unsigned; the
artifacts are GPG-signed.

What kind of bump a change needs is in [`docs/versioning.md`](docs/versioning.md).

## Secrets

`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (Central Portal token),
`SIGNING_IN_MEMORY_KEY` (ASCII-armored GPG private key) and
`SIGNING_IN_MEMORY_KEY_PASSWORD`, passed as the vanniktech `ORG_GRADLE_PROJECT_*`
properties.

## Cutting a release

1. Pick `X.Y.Z` per [`docs/versioning.md`](docs/versioning.md).
2. Run the conformance sweep against a radio. The meshtastic-mcp simulated TCP radio is
   acceptable:

   ```sh
   ./gradlew :samples:cli:installDist
   samples/cli/build/install/cli/bin/cli conformance \
       --transport=tcp:meshtastic.local \
       --peer-node='!aabbccdd' \
       --candidate=vX.Y.Z \
       --output docs/release-history/vX.Y.Z-conformance.md
   ```

   Every FAIL is either fixed or explained in the release's changelog section. Flags are
   in [`samples/cli/README.md`](samples/cli/README.md#pre-release-conformance-sweep);
   the scenarios are in [`docs/manual-tests.md`](docs/manual-tests.md).
3. On a branch, run `scripts/changelog.sh cut X.Y.Z`. That moves `## [Unreleased]` under
   a dated `## [X.Y.Z]` heading and touches nothing else. It refuses an empty Unreleased.
4. If the public API changed, `./gradlew updateKotlinAbi` and commit the `api/` dumps.
5. Commit `chore(release): X.Y.Z` with the transcript (signed off), open the PR and
   merge it.
6. `gh workflow run release.yml --repo meshtastic/meshtastic-sdk -f version=X.Y.Z`. Add
   `-f dry_run=true` to run every gate without tagging or publishing; a dry run may start
   from any branch. Pushing a `vX.Y.Z` tag on `main` runs the same workflow.

## What the workflow checks, in order

1. The commit is on `main` (skipped for a dry run).
2. Any existing `vX.Y.Z` tag points at this commit.
3. `scripts/changelog.sh notes X.Y.Z` finds a non-empty section. It becomes the GitHub
   Release body verbatim, under an install snippet and a `**BREAKING**` line when the
   section has `### Breaking`.
4. Every check the `main` ruleset requires passed on this commit
   (`scripts/release-checks.sh green-ci`). That is where the iOS tests ran; this Linux
   runner cannot run them.
5. It tags `vX.Y.Z` locally and confirms axion resolves `X.Y.Z`.
6. `./gradlew assemble` and `./gradlew check` without the samples, then
   `publishToMavenLocal` with signing.
7. No staged POM or Gradle module depends on a `-SNAPSHOT`
   (`scripts/release-checks.sh no-snapshots`). Central rejects that only after upload.
8. If `sdk-core` `X.Y.Z` is already on `repo1.maven.org` the publish is skipped, so a
   re-run is safe.

Then it attests every staged artifact, pushes the tag if origin lacks it, runs
`publishAndReleaseToMavenCentral`, and creates or updates the GitHub Release.

## After releasing

`repo1.maven.org` lags the Central Portal by 10 to 30 minutes. Downstream bumps wait
until `https://repo1.maven.org/maven2/org/meshtastic/sdk-core/X.Y.Z/` resolves.
Maven Central has no unpublish; a bad release is superseded by the next patch, as
[`docs/versioning.md`](docs/versioning.md#yanking-a-release) describes.
