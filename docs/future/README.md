# Future / roadmap notes

> 🔮 **Forward-looking design sketches.** These documents describe work
> that is **not yet shipped** and may never ship in the form described.
> They are useful as design context but are **not commitments**.

## Contents

| File | Status | Notes |
|---|---|---|
| [`wasm-rpc-roadmap.md`](./wasm-rpc-roadmap.md) | Post-1.0 idea | Sketches the wasm/RPC story for browser-side use. References the additive artifacts (`sdk-rpc`, `sdk-host-rpc-server`, `sdk-transport-rpc`, `sdk-transport-mqtt-proxy`) that would land if/when this is pursued. |

## Why keep these?

Removing them would lose the design rationale. Keeping them adjacent to
the canonical docs would imply they are part of the shipping spec. This
directory is the compromise: discoverable, but quarantined behind a
README that flags them as roadmap-only.

When a roadmap document graduates to a shipping decision, **promote it**
into [`docs/decisions/`](../decisions/) as a proper ADR and delete the
copy here.
