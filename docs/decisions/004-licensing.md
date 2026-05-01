# ADR 004 — Licensing and contribution model

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads (Meshtastic org)
**Supersedes:** none
**Related:** [`../../LICENSE`](../../LICENSE), [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md), ADR-000 (charter)

---

## Context

`meshtastic-sdk` lives in the `meshtastic` GitHub organization alongside `firmware`, `protobufs`, `Meshtastic-Android`, `Meshtastic-Apple`, and `mqtt-client`. The org's existing projects use **GNU GPL v3** for application code (`firmware`, `Meshtastic-Android`, `Meshtastic-Apple`) and `mqtt-client` is also GPL-3.0. The protocol schema (`meshtastic/protobufs`) is GPL-3.0 with the explicit understanding that the wire protocol itself is not copyrightable.

We need to pick:

1. The license for this repository.
2. The contribution mechanism (CLA vs DCO vs neither).
3. The provenance posture for contributions that draw on sibling Meshtastic-org projects.

## Decision

### License

**GNU General Public License v3.0 or later (GPL-3.0-or-later).** Full text in `LICENSE` at the repo root. Every source file carries the standard SPDX header:

```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) <year> Meshtastic LLC
 */
```

Generated code (`proto/build/generated/...`) is exempt from headers; the generator itself is GPL-clean (Wire is Apache-2.0, compatible with GPL-3.0 distribution).

### Contribution mechanism

**Developer Certificate of Origin (DCO) sign-off.** Every commit MUST include a `Signed-off-by:` trailer matching the author. No separate Contributor License Agreement (CLA). The PR template surfaces a checkbox and a link to [`developercertificate.org`](https://developercertificate.org/).

CI gate: a `dco-bot` workflow blocks merge until every commit on the PR is signed off.

### Provenance for code lifted from sibling Meshtastic-org projects

Because this SDK lives *inside* the `meshtastic` org, GPL-compatible reuse from `Meshtastic-Android`, `Meshtastic-Apple`, `firmware`, or `mqtt-client` is permitted. When code is lifted (rather than re-derived from the protocol):

1. **Preserve attribution in the file header.** Add a second copyright line crediting the source repo and original author(s).
2. **Note the origin in the commit message** (e.g., `Origin: Meshtastic-Android core/network/.../HeartbeatSender.kt @ <commit>`).
3. **Keep the GPL-3.0 SPDX line.** No license change — both source and destination are GPL-3.0.

Code from outside the `meshtastic` org follows ordinary GPL-compatibility rules: GPL-3.0-or-later code can be lifted with attribution; permissive licenses (MIT/Apache/BSD) are allowed if the file header preserves the original notice.

### Third-party dependencies

Dependencies (Wire, Ktor, kotlinx-*, Kable, jSerialComm, SQLDelight, etc.) are all under permissive licenses (Apache-2.0, MIT, EPL) and are GPL-3.0 distributable. No GPL-incompatible deps allowed.

A `LICENSES.md` at the repo root summarizes runtime dependencies and their licenses; CI generates it from Gradle's dependency resolution.

## Alternatives considered

- **MIT or Apache-2.0.** Rejected — the rest of the Meshtastic org is GPL-3.0; mixing licenses fragments contributor expectations and complicates downstream re-bundling.
- **LGPL-3.0.** Considered for "library, not application" reasons. Rejected: the org's existing MQTT KMP library (`mqtt-client`) is GPL-3.0, and consistency across org-published Kotlin libraries simplifies dependency consumption. Linking-clause distinctions are a marginal win for a domain-specific protocol library.
- **CLA.** Rejected as friction with no benefit at this scale. DCO is sufficient for an org-stewarded GPL project.
- **Copyright assignment to a foundation.** Out of scope for now — the org already accumulates copyright via DCO sign-offs; revisit if the SDK becomes a multi-vendor effort.

## Consequences

- **Downstream consumers must comply with GPL-3.0.** Any application linking this SDK (statically or dynamically) becomes a derived work and must itself be GPL-3.0-compatible. This matches the existing posture for `Meshtastic-Android` and `mqtt-client`; we are not making the situation worse for new consumers.
- **No clean-room requirement.** Because both source and destination repos are under the org's GPL-3.0 umbrella, contributors may reference and lift code from `Meshtastic-Android` / `Meshtastic-Apple` / `firmware` directly, with attribution. This significantly speeds up porting work.
- **DCO sign-off becomes a contributor habit.** Documented in `CONTRIBUTING.md`; sample git config snippet provided.
- **License headers are linted.** A `:checkLicenseHeaders` Gradle task (added in Phase 0) fails on missing/wrong headers.
