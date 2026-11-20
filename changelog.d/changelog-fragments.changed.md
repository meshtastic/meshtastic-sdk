- A PR records its changelog entry as a `changelog.d/<name>.<section>.md` fragment instead of
  editing `CHANGELOG.md`, so concurrent PRs no longer conflict. `scripts/changelog.sh cut` folds
  the fragments into `## [Unreleased]` before it cuts, and `fold`, `preview` and `check` are new.
