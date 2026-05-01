---
name: release-readiness
description: Use before final merge recommendations or version tag release candidates to verify quality gates and SemVer decisions.
---

# Release Readiness

Use this skill before final merge recommendations or version tag release candidates.

## Goals
- Ensure quality gates and policy checks are satisfied.
- Make SemVer and API compatibility decisions explicit.
- Reduce release regressions from process gaps.

## Required Context
- AGENTS.md
- CONTRIBUTING.md
- docs/ci-cd.md
- docs/versioning.md
- docs/manual-tests.md

## Workflow
1. Validate local prerequisites and submodule state.
2. Run required quality checks (targeted first, full gate before finish).
3. Evaluate API impact and baseline updates.
4. Evaluate SemVer implications.
5. Confirm required docs and manual test artifacts are present.
6. Produce release verdict with blockers and confidence level.

## Command Set
- ./gradlew detekt ktlintCheck
- ./gradlew checkKotlinAbi
- ./gradlew check
- ./gradlew updateKotlinAbi (intentional API changes only)

## Deliverable
- Gate status table
- API and SemVer decision
- Blockers and recommended next actions
