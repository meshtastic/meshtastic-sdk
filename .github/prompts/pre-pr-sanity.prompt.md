---
name: pre-pr-sanity
description: Final pre-PR compliance and quality sweep
mode: ask
---

Run a pre-PR quality sweep for this repository.

Checklist:
1. Confirm scope is minimal and aligned to task.
2. Confirm module boundary compliance from docs/architecture/module-graph.md.
3. Confirm public API shape and compatibility workflow:
   - checkKotlinAbi passes
   - updateKotlinAbi included only for intentional API change
4. Confirm architecture rules and lint:
   - :core:verifyModuleBoundary
   - detekt
   - spotlessCheck
5. Confirm behavior-changing code has tests.
6. Confirm docs updates for protocol, API, or contributor workflow changes.
7. Confirm DCO signoff requirement is visible to author.
8. Provide a merge readiness verdict with blocking and non-blocking items.

Prefer targeted commands while iterating, then run ./gradlew check before final recommendation.
