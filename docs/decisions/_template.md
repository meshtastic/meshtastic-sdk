# ADR-NNN: <Short, decision-oriented title>

> Use this template for every new ADR. Filename: `NNN-kebab-case-title.md`. NNN is a zero-padded sequential integer (`008-…`, `009-…`).

| Field | Value |
|---|---|
| **Status** | Proposed / Accepted / Superseded by ADR-MMM / Deprecated |
| **Date** | YYYY-MM-DD |
| **Deciders** | (PR reviewers / quorum) |
| **Supersedes** | (link to prior ADR if this replaces it) |
| **Related** | (links to `SPEC.md` sections, other ADRs, issues) |

## Context

What's the situation that demands a decision? Be concrete. Include:
- The problem statement
- Constraints (technical, organisational, legal)
- Forces in tension (perf vs ergonomics, ABI stability vs API expressiveness, etc.)

## Decision

The actual choice. One sentence first, then a paragraph with the specifics of *what* we're committing to. No rationale yet — that's the next section.

## Rationale

Why we picked this option. Reference each force in the Context.

## Alternatives considered

| Option | Why not |
|---|---|
| (Option A) | (concise reason) |
| (Option B) | (concise reason) |

## Consequences

### Positive
- (what this unlocks)

### Negative / costs
- (what this commits us to maintain)
- (what gets harder)

### Follow-ups
- [ ] Track in `SPEC.md` §X
- [ ] Update `CONTRIBUTING.md` if this changes contributor workflow
- [ ] Add a detekt `ForbiddenImport` entry if this introduces a forbidden pattern

## References

- [Link to PR that landed this ADR]
- [External docs / RFCs]
