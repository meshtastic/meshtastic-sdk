---
name: docs-sync-guard
description: Use when behavior, protocol, architecture, API, or workflow changed and docs must be synchronized with minimal drift.
---

# Docs Sync Guard

Use this skill when code changes may alter expected behavior, API semantics, protocol handling, or contributor workflow.

## Goals
- Keep documentation aligned with implementation.
- Minimize drift between source code and reference docs.
- Preserve link-first, non-duplicative documentation style.

## Required Context
- AGENTS.md
- docs/SPEC.md
- docs/protocol.md
- docs/api-reference.md
- docs/error-taxonomy.md
- docs/architecture/module-graph.md
- docs/ci-cd.md
- CONTRIBUTING.md

## Trigger Conditions
Run this skill when any of the following changed:
- handshake or protocol behavior
- public API shape or outcome contracts
- module boundaries or architecture constraints
- build, test, lint, release, or contribution workflow

## Workflow
1. Identify behavior and contract changes from code diff.
2. Map each change to specific docs that should be updated.
3. Edit docs with minimal, precise wording and canonical links.
4. Verify terminology consistency across docs.
5. Produce a docs impact summary for reviewers.

## Validation
- Confirm every behavior-affecting change has either:
  - corresponding doc update, or
  - explicit no-doc-change rationale.

## Deliverable
- Updated docs list
- Rationale per doc change
- Open documentation gaps (if any)
