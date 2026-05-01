---
name: ci-failure-triage
description: Use when local or CI checks fail to quickly classify root cause and apply a minimal safe fix.
---

# CI Failure Triage

Use this skill when local or CI checks fail.

## Goals
- Find root cause quickly.
- Apply minimal safe fix.
- Revalidate only necessary scopes first.

## Required Context
- AGENTS.md
- CONTRIBUTING.md
- docs/ci-cd.md
- docs/versioning.md

## Triage Buckets
- Build setup or dependency issues.
- Lint or formatting failures.
- Architecture rule failures (detekt `ForbiddenImport` + `:core:verifyModuleBoundary`).
- API compatibility failures (checkKotlinAbi/updateKotlinAbi mismatch).
- Unit or integration regressions.
- Environment issues (JDK 21, Android SDK 35, Xcode, submodules).

## Workflow
1. Capture first failing task and first meaningful stack trace.
2. Classify into a triage bucket.
3. Propose smallest fix.
4. Re-run targeted task.
5. If green, expand to broader verification.
6. Summarize root cause and confidence level.

## Validation Commands
- ./gradlew <failingTask>
- ./gradlew detekt ktlintCheck
- ./gradlew checkKotlinAbi
- ./gradlew check

## Deliverable
Provide:
- root cause
- exact fix
- targeted reruns performed
- final status and remaining risk
