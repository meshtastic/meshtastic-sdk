<!--
Thank you for contributing to meshtastic-sdk!
Fill out the sections below. Delete any that don't apply.
-->

## Summary

<!-- One or two sentences: what does this PR do? -->

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking change (will require a SemVer-MINOR pre-1.0 / SemVer-MAJOR post-1.0 bump)
- [ ] Documentation only
- [ ] Infrastructure / CI / build
- [ ] Proto submodule bump
- [ ] ADR (Architecture Decision Record)

## Related issue / discussion

<!-- Link the issue: Fixes #123 / Refs #456. For non-trivial changes, link the design discussion. -->

## Affirmations

- [ ] All commits are signed off (DCO — `git commit -s`).
- [ ] I have read [`CONTRIBUTING.md`](../CONTRIBUTING.md).
- [ ] If this changes the public API, I have run `./gradlew updateKotlinAbi` and committed the regenerated `api/*.api` files.
- [ ] If this changes wire behavior, I have updated [`docs/protocol.md`](../docs/protocol.md) and cited firmware / sibling-app sources for verification.
- [ ] If this is a non-trivial design change, I have added or updated an ADR under [`docs/decisions/`](../docs/decisions/).
- [ ] I have run `./gradlew check` locally and it passes.

## How was this verified?

<!--
Describe testing — unit tests, manual device test (cite manual-tests.md test ID),
emulator/simulator runs. For wire-protocol changes, list the firmware version(s)
and reference clients (Android/Apple) you cross-checked against.
-->

## Notes for reviewers

<!-- Anything else the reviewer should know. Tricky areas, open questions, follow-ups. -->
