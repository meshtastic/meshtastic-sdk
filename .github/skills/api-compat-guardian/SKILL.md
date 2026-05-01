---
name: api-compat-guardian
description: Use when evaluating public API impact, checkKotlinAbi/updateKotlinAbi expectations, SemVer implications, and ADR-005 response-shape compliance.
---

# API Compatibility Guardian

Use this skill whenever changes may affect published public APIs.

## Goals
- Prevent accidental API breakage.
- Enforce API shape conventions.
- Keep versioning implications explicit.

## Required Context
- AGENTS.md
- docs/decisions/005-api-shape.md
- docs/versioning.md
- docs/api-reference.md
- CONTRIBUTING.md

## Guardrails
- Do not introduce kotlin.Result in public API.
- Preserve throw vs AdminResult vs Flow response-shape policy.
- Include api/*.api changes only for intentional public API updates.

## Workflow
1. Determine whether touched symbols are public.
2. Classify API impact: additive, behavioral, breaking.
3. Run checkKotlinAbi.
4. Run updateKotlinAbi only when intentional API changes are confirmed.
5. Review api/*.api diff for unintended churn.
6. Report required SemVer impact and follow-up actions.

## Validation Commands
- ./gradlew checkKotlinAbi
- ./gradlew updateKotlinAbi
- ./gradlew check

## Deliverable
Return a compact API verdict:
- API changed: yes or no
- expected versioning impact
- baseline files updated correctly: yes or no
