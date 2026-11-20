<!--
Fill out each section and delete any that don't apply.
-->

## Summary

<!-- One or two sentences: what this PR does and why. -->

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking change (a minor bump pre-1.0, a major bump from 1.0)
- [ ] Documentation only
- [ ] Infrastructure, CI or build
- [ ] `org.meshtastic:protobufs` bump
- [ ] ADR (Architecture Decision Record)

## Related issue

<!-- Fixes #123 or Refs #456. For a non-trivial change, link the design discussion. -->

## Affirmations

- [ ] Every commit is signed off (DCO, `git commit -s`).
- [ ] I have read [`CONTRIBUTING.md`](../CONTRIBUTING.md).
- [ ] A consumer-visible change has a `changelog.d/` fragment, and `CHANGELOG.md` is untouched.
- [ ] If the public API changed, I ran `./gradlew updateKotlinAbi` and committed the regenerated `api/` dumps.
- [ ] If wire behavior changed, I updated [`docs/protocol.md`](../docs/protocol.md) and cited firmware and client sources.
- [ ] If this is a non-trivial design change, I added or updated an ADR under [`docs/decisions/`](../docs/decisions/).
- [ ] `./gradlew check` passes locally.

## How was this verified?

<!--
Unit tests, a manual device test (cite the manual-tests.md ID), emulator or simulator runs.
For a wire-protocol change, list the firmware versions and the clients (Android, Apple) you cross-checked.
-->

## Notes for reviewers

<!-- Tricky areas, open questions, follow-ups. -->
