# Future and roadmap notes

This directory holds design sketches for work that has not shipped and may never ship in the form described. They are design context, not commitments.

## Contents

| File | Status | Notes |
|---|---|---|
| [`wasm-rpc-roadmap.md`](./wasm-rpc-roadmap.md) | Post-1.0 idea | A remote engine for hosts that cannot reach the node themselves, through the additive modules `:rpc`, `:transport-rpc` and `:host-rpc-server`. The web targets it once covered already ship. |

## Why keep these?

Deleting them would lose the design rationale, and placing them beside the shipping docs would suggest they are part of the spec. This directory keeps them discoverable but marked as roadmap-only.

When a roadmap page becomes a shipping decision, promote it to an ADR in [`docs/decisions/`](../decisions/) and delete the copy here.
