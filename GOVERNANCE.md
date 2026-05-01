# Governance

This project (`meshtastic-sdk`) is a community-driven Kotlin Multiplatform SDK
for Meshtastic devices. It follows a lightweight, do-ocracy model anchored in
the [Meshtastic](https://meshtastic.org) ecosystem.

## Roles

- **Maintainers** — listed in [`CODEOWNERS`](CODEOWNERS). They review and merge
  pull requests, cut releases, and own the architectural decisions captured in
  [`docs/decisions/`](docs/decisions/).
- **Contributors** — anyone who opens an issue or PR. The
  [Contributor Covenant 2.1](CODE_OF_CONDUCT.md) applies to every interaction
  in this repository.
- **Users** — downstream consumers of the SDK. Issues and discussions are the
  primary channel for feedback.

## Decision Making

1. **Day-to-day changes** (bug fixes, doc edits, additive non-breaking work)
   land via pull request with one maintainer approval and a green
   `./gradlew check`.
2. **Behavior changes that affect public API, wire protocol, or module
   boundaries** require an Architecture Decision Record (see
   [`docs/decisions/_template.md`](docs/decisions/_template.md)) merged
   alongside the implementation.
3. **Release cadence and SemVer policy** are governed by
   [`docs/versioning.md`](docs/versioning.md). Pre-1.0 minor versions may
   contain breaking changes; every breaking change must be called out in
   [`CHANGELOG.md`](CHANGELOG.md).
4. **Disagreements** that cannot be resolved in the PR thread are escalated to
   a maintainer vote. A simple majority of active maintainers carries the
   decision; ties are broken by the longest-tenured maintainer.

## Becoming a Maintainer

A contributor may be invited to become a maintainer after sustained,
high-quality participation — typically several merged PRs across multiple
modules and demonstrated familiarity with the architecture documents listed in
[`AGENTS.md`](AGENTS.md). Existing maintainers nominate and approve by
consensus.

## Stepping Down

Maintainers may step down at any time by opening a PR removing themselves from
[`CODEOWNERS`](CODEOWNERS). Inactive maintainers (no review or commit activity
for 12 months) may be moved to emeritus status by the remaining maintainers.

## Code of Conduct Enforcement

Reports are handled by the maintainer team per
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). When the report involves a
maintainer, the remaining maintainers handle the review without that
individual present.

## Changes to This Document

Governance changes follow the same ADR process as architectural changes: open
a PR with a brief rationale, allow at least one week for community comment,
and require approval from a majority of maintainers before merging.
